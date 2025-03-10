/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server;

import static android.telephony.SubscriptionManager.INVALID_SIM_SLOT_INDEX;
import static android.telephony.TelephonyManager.ACTION_MULTI_SIM_CONFIG_CHANGED;
import static android.telephony.TelephonyRegistryManager.SIM_ACTIVATION_TYPE_DATA;
import static android.telephony.TelephonyRegistryManager.SIM_ACTIVATION_TYPE_VOICE;

import static java.util.Arrays.copyOf;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.compat.CompatChanges;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledSince;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.telecom.TelecomManager;
import android.telephony.Annotation;
import android.telephony.Annotation.RadioPowerState;
import android.telephony.Annotation.SrvccState;
import android.telephony.BarringInfo;
import android.telephony.CallAttributes;
import android.telephony.CallQuality;
import android.telephony.CellIdentity;
import android.telephony.CellInfo;
import android.telephony.CellSignalStrength;
import android.telephony.CellSignalStrengthCdma;
import android.telephony.CellSignalStrengthGsm;
import android.telephony.CellSignalStrengthLte;
import android.telephony.CellSignalStrengthNr;
import android.telephony.CellSignalStrengthTdscdma;
import android.telephony.CellSignalStrengthWcdma;
import android.telephony.DisconnectCause;
import android.telephony.LinkCapacityEstimate;
import android.telephony.LocationAccessPolicy;
import android.telephony.PhoneCapability;
import android.telephony.PhoneStateListener;
import android.telephony.PhysicalChannelConfig;
import android.telephony.PreciseCallState;
import android.telephony.PreciseDataConnectionState;
import android.telephony.PreciseDisconnectCause;
import android.telephony.Rlog;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyDisplayInfo;
import android.telephony.TelephonyManager;
import android.telephony.data.ApnSetting;
import android.telephony.emergency.EmergencyNumber;
import android.telephony.ims.ImsReasonInfo;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.LocalLog;
import android.util.Pair;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IBatteryStats;
import com.android.internal.telephony.IOnSubscriptionsChangedListener;
import com.android.internal.telephony.IPhoneStateListener;
import com.android.internal.telephony.ITelephonyRegistry;
import com.android.internal.telephony.TelephonyPermissions;
import com.android.internal.telephony.util.TelephonyUtils;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.am.BatteryStatsService;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Since phone process can be restarted, this class provides a centralized place
 * that applications can register and be called back from.
 *
 * Change-Id: I450c968bda93767554b5188ee63e10c9f43c5aa4 fixes bugs 16148026
 * and 15973975 by saving the phoneId of the registrant and then using the
 * phoneId when deciding to to make a callback. This is necessary because
 * a subId changes from to a placeholder value when a SIM is removed and thus won't
 * compare properly. Because getPhoneIdFromSubId(int subId) handles
 * the placeholder value conversion we properly do the callbacks.
 *
 * Eventually we may want to remove the notion of placeholder value but for now this
 * looks like the best approach.
 */
@VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
public class TelephonyRegistry extends ITelephonyRegistry.Stub {
    private static final String TAG = "TelephonyRegistry";
    private static final boolean DBG = false; // STOPSHIP if true
    private static final boolean DBG_LOC = false; // STOPSHIP if true
    private static final boolean VDBG = false; // STOPSHIP if true

    private static class Record {
        Context context;

        String callingPackage;
        String callingFeatureId;

        IBinder binder;

        TelephonyRegistryDeathRecipient deathRecipient;

        IPhoneStateListener callback;
        IOnSubscriptionsChangedListener onSubscriptionsChangedListenerCallback;
        IOnSubscriptionsChangedListener onOpportunisticSubscriptionsChangedListenerCallback;

        int callerUid;
        int callerPid;

        Set<Integer> eventList;

        int subId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;

        int phoneId = SubscriptionManager.INVALID_SIM_SLOT_INDEX;

        boolean matchTelephonyCallbackEvent(int event) {
            return (callback != null) && (this.eventList.contains(event));
        }

        boolean matchOnSubscriptionsChangedListener() {
            return (onSubscriptionsChangedListenerCallback != null);
        }

        boolean matchOnOpportunisticSubscriptionsChangedListener() {
            return (onOpportunisticSubscriptionsChangedListenerCallback != null);
        }

        boolean canReadCallLog() {
            try {
                return TelephonyPermissions.checkReadCallLog(
                        context, subId, callerPid, callerUid, callingPackage, callingFeatureId);
            } catch (SecurityException e) {
                return false;
            }
        }

        @Override
        public String toString() {
            return "{callingPackage=" + pii(callingPackage) + " callerUid=" + callerUid + " binder="
                    + binder + " callback=" + callback
                    + " onSubscriptionsChangedListenererCallback="
                    + onSubscriptionsChangedListenerCallback
                    + " onOpportunisticSubscriptionsChangedListenererCallback="
                    + onOpportunisticSubscriptionsChangedListenerCallback + " subId=" + subId
                    + " phoneId=" + phoneId + " events=" + eventList + "}";
        }
    }

    /**
     * Wrapper class to facilitate testing -- encapsulates bits of configuration that are
     * normally fetched from static methods with many dependencies.
     */
    public static class ConfigurationProvider {
        /**
         * @return The per-pid registration limit for PhoneStateListeners, as set from DeviceConfig
         * @noinspection ConstantConditions
         */
        public int getRegistrationLimit() {
            return Binder.withCleanCallingIdentity(() ->
                    DeviceConfig.getInt(DeviceConfig.NAMESPACE_TELEPHONY,
                            TelephonyCallback.FLAG_PER_PID_REGISTRATION_LIMIT,
                            TelephonyCallback.DEFAULT_PER_PID_REGISTRATION_LIMIT));
        }

        /**
         * @param uid uid to check
         * @return Whether enforcement of the per-pid registation limit for PhoneStateListeners is
         *         enabled in PlatformCompat for the given uid.
         * @noinspection ConstantConditions
         */
        public boolean isRegistrationLimitEnabledInPlatformCompat(int uid) {
            return Binder.withCleanCallingIdentity(() -> CompatChanges.isChangeEnabled(
                    TelephonyCallback.PHONE_STATE_LISTENER_LIMIT_CHANGE_ID, uid));
        }

        /**
         * See {@link TelecomManager#ENABLE_GET_CALL_STATE_PERMISSION_PROTECTION} for more
         * information.
         * @noinspection ConstantConditions
         */
        public boolean isCallStateReadPhoneStateEnforcedInPlatformCompat(String packageName,
                UserHandle userHandle) {
            return Binder.withCleanCallingIdentity(() -> CompatChanges.isChangeEnabled(
                    TelecomManager.ENABLE_GET_CALL_STATE_PERMISSION_PROTECTION, packageName,
                    userHandle));
        }

        /**
         * To check the SDK version for
         * {@link android.telephony.TelephonyCallback.ActiveDataSubscriptionIdListener} should add
         * {@link android.Manifest.permission#READ_PHONE_STATE} since Android 12.
         * @noinspection ConstantConditions
         */
        public boolean isActiveDataSubIdReadPhoneStateEnforcedInPlatformCompat(String packageName,
                UserHandle userHandle) {
            return Binder.withCleanCallingIdentity(() -> CompatChanges.isChangeEnabled(
                    REQUIRE_READ_PHONE_STATE_PERMISSION_FOR_ACTIVE_DATA_SUB_ID, packageName,
                    userHandle));
        }

        /**
         * To check the SDK version for
         * {@link android.telephony.TelephonyCallback.CellInfoListener} should add
         * {@link android.Manifest.permission#READ_PHONE_STATE} since Android 12.
         * @noinspection ConstantConditions
         */
        public boolean isCellInfoReadPhoneStateEnforcedInPlatformCompat(String packageName,
                UserHandle userHandle) {
            return Binder.withCleanCallingIdentity(() -> CompatChanges.isChangeEnabled(
                    REQUIRE_READ_PHONE_STATE_PERMISSION_FOR_CELL_INFO, packageName, userHandle));
        }

        /**
         * To check the SDK version for
         * {@link android.telephony.TelephonyCallback.DisplayInfoListener} should remove
         * {@link android.Manifest.permission#READ_PHONE_STATE} since Android 12.
         * @noinspection ConstantConditions
         */
        public boolean isDisplayInfoReadPhoneStateEnforcedInPlatformCompat(String packageName,
                UserHandle userHandle) {
            return Binder.withCleanCallingIdentity(() -> CompatChanges.isChangeEnabled(
                    REQUIRE_READ_PHONE_STATE_PERMISSION_FOR_DISPLAY_INFO, packageName, userHandle));
        }

        /**
         * Support backward compatibility for {@link android.telephony.TelephonyDisplayInfo}.
         *
         * @noinspection ConstantConditions
         */
        public boolean isDisplayInfoNrAdvancedSupported(String packageName,
                UserHandle userHandle) {
            return Binder.withCleanCallingIdentity(() -> CompatChanges.isChangeEnabled(
                    DISPLAY_INFO_NR_ADVANCED_SUPPORTED, packageName, userHandle));
        }
    }

    private final Context mContext;

    private ConfigurationProvider mConfigurationProvider;

    // access should be inside synchronized (mRecords) for these two fields
    private final ArrayList<IBinder> mRemoveList = new ArrayList<IBinder>();
    private final ArrayList<Record> mRecords = new ArrayList<Record>();

    private final IBatteryStats mBatteryStats;

    private final AppOpsManager mAppOps;

    private boolean mHasNotifySubscriptionInfoChangedOccurred = false;

    private boolean mHasNotifyOpportunisticSubscriptionInfoChangedOccurred = false;

    private int mNumPhones;

    private int[] mCallState;

    private String[] mCallIncomingNumber;

    private ServiceState[] mServiceState;

    private int[] mVoiceActivationState;

    private int[] mDataActivationState;

    private boolean[] mUserMobileDataState;

    private TelephonyDisplayInfo[] mTelephonyDisplayInfos;

    private SignalStrength[] mSignalStrength;

    private boolean[] mMessageWaiting;

    private boolean[] mCallForwarding;

    private int[] mDataActivity;

    // Connection state of default APN type data (i.e. internet) of phones
    private int[] mDataConnectionState;

    private CellIdentity[] mCellIdentity;

    private int[] mDataConnectionNetworkType;

    private ArrayList<List<CellInfo>> mCellInfo = null;

    private Map<Integer, List<EmergencyNumber>> mEmergencyNumberList;

    private EmergencyNumber[] mOutgoingSmsEmergencyNumber;

    private EmergencyNumber[] mOutgoingCallEmergencyNumber;

    private CallQuality[] mCallQuality;

    private CallAttributes[] mCallAttributes;

    // network type of the call associated with the mCallAttributes and mCallQuality
    private int[] mCallNetworkType;

    private int[] mSrvccState;

    private int mDefaultSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;

    private int mDefaultPhoneId = SubscriptionManager.INVALID_SIM_SLOT_INDEX;

    private int[] mRingingCallState;

    private int[] mForegroundCallState;

    private int[] mBackgroundCallState;

    private PreciseCallState[] mPreciseCallState;

    private int[] mCallDisconnectCause;

    private List<ImsReasonInfo> mImsReasonInfo = null;

    private int[] mCallPreciseDisconnectCause;

    private List<BarringInfo> mBarringInfo = null;

    private boolean mCarrierNetworkChangeState = false;

    private PhoneCapability mPhoneCapability = null;

    private int mActiveDataSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;

    @RadioPowerState
    private int mRadioPowerState = TelephonyManager.RADIO_POWER_UNAVAILABLE;

    private final LocalLog mLocalLog = new LocalLog(200);

    private final LocalLog mListenLog = new LocalLog(200);

    private List<List<PhysicalChannelConfig>> mPhysicalChannelConfigs;

    private boolean[] mIsDataEnabled;

    private int[] mDataEnabledReason;

    private int[] mAllowedNetworkTypeReason;
    private long[] mAllowedNetworkTypeValue;

    private List<List<LinkCapacityEstimate>> mLinkCapacityEstimateLists;

    /**
     * Per-phone map of precise data connection state. The key of the map is the pair of transport
     * type and APN setting. This is the cache to prevent redundant callbacks to the listeners.
     * A precise data connection with state {@link TelephonyManager#DATA_DISCONNECTED} removes
     * its entry from the map.
     */
    private List<Map<Pair<Integer, ApnSetting>, PreciseDataConnectionState>>
            mPreciseDataConnectionStates;
    /**
     * Support backward compatibility for {@link android.telephony.TelephonyDisplayInfo}.
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = Build.VERSION_CODES.S)
    private static final long DISPLAY_INFO_NR_ADVANCED_SUPPORTED = 181658987L;

    /**
     * To check the SDK version for
     * {@link android.telephony.TelephonyCallback.DisplayInfoListener} should remove
     * {@link android.Manifest.permission#READ_PHONE_STATE} since Android 12.
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = Build.VERSION_CODES.S)
    private static final long REQUIRE_READ_PHONE_STATE_PERMISSION_FOR_DISPLAY_INFO = 183164979L;

    /**
     * To check the SDK version for
     * {@link android.telephony.TelephonyCallback.ActiveDataSubscriptionIdListener} should add
     * {@link android.Manifest.permission#READ_PHONE_STATE} since Android 12.
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = Build.VERSION_CODES.S)
    private static final long REQUIRE_READ_PHONE_STATE_PERMISSION_FOR_ACTIVE_DATA_SUB_ID
            = 182478738L;

    /**
     * To check the SDK version for {@link android.telephony.TelephonyCallback.CellInfoListener}
     * should add {@link android.Manifest.permission#READ_PHONE_STATE} since Android 12.
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = Build.VERSION_CODES.S)
    private static final long REQUIRE_READ_PHONE_STATE_PERMISSION_FOR_CELL_INFO = 184323934L;

    private static final Set<Integer> REQUIRE_PRECISE_PHONE_STATE_PERMISSION;
    static {
        REQUIRE_PRECISE_PHONE_STATE_PERMISSION = new HashSet<Integer>();
        REQUIRE_PRECISE_PHONE_STATE_PERMISSION.add(
                TelephonyCallback.EVENT_PRECISE_DATA_CONNECTION_STATE_CHANGED);
        REQUIRE_PRECISE_PHONE_STATE_PERMISSION.add(
                TelephonyCallback.EVENT_DATA_CONNECTION_REAL_TIME_INFO_CHANGED);
        REQUIRE_PRECISE_PHONE_STATE_PERMISSION.add(
                TelephonyCallback.EVENT_PRECISE_CALL_STATE_CHANGED);
        REQUIRE_PRECISE_PHONE_STATE_PERMISSION.add(
                TelephonyCallback.EVENT_CALL_DISCONNECT_CAUSE_CHANGED);
        REQUIRE_PRECISE_PHONE_STATE_PERMISSION.add(
                TelephonyCallback.EVENT_CALL_ATTRIBUTES_CHANGED);
        REQUIRE_PRECISE_PHONE_STATE_PERMISSION.add(
                TelephonyCallback.EVENT_IMS_CALL_DISCONNECT_CAUSE_CHANGED);
        REQUIRE_PRECISE_PHONE_STATE_PERMISSION.add(TelephonyCallback.EVENT_REGISTRATION_FAILURE);
        REQUIRE_PRECISE_PHONE_STATE_PERMISSION.add(TelephonyCallback.EVENT_BARRING_INFO_CHANGED);
        REQUIRE_PRECISE_PHONE_STATE_PERMISSION.add(
                TelephonyCallback.EVENT_PHYSICAL_CHANNEL_CONFIG_CHANGED);
        REQUIRE_PRECISE_PHONE_STATE_PERMISSION.add(
                TelephonyCallback.EVENT_DATA_ENABLED_CHANGED);
        REQUIRE_PRECISE_PHONE_STATE_PERMISSION.add(
                TelephonyCallback.EVENT_LINK_CAPACITY_ESTIMATE_CHANGED);
    }

    private boolean isLocationPermissionRequired(Set<Integer> events) {
        return events.contains(TelephonyCallback.EVENT_CELL_LOCATION_CHANGED)
                || events.contains(TelephonyCallback.EVENT_CELL_INFO_CHANGED)
                || events.contains(TelephonyCallback.EVENT_REGISTRATION_FAILURE)
                || events.contains(TelephonyCallback.EVENT_BARRING_INFO_CHANGED);
    }

    private boolean isPhoneStatePermissionRequired(Set<Integer> events, String callingPackage,
            UserHandle userHandle) {
        if (events.contains(TelephonyCallback.EVENT_CALL_FORWARDING_INDICATOR_CHANGED)
                || events.contains(TelephonyCallback.EVENT_MESSAGE_WAITING_INDICATOR_CHANGED)
                || events.contains(TelephonyCallback.EVENT_EMERGENCY_NUMBER_LIST_CHANGED)) {
            return true;
        }

        // Only check READ_PHONE_STATE for CALL_STATE_CHANGED for Android 12 or above.
        if ((events.contains(TelephonyCallback.EVENT_LEGACY_CALL_STATE_CHANGED)
                || events.contains(TelephonyCallback.EVENT_CALL_STATE_CHANGED))
                && mConfigurationProvider.isCallStateReadPhoneStateEnforcedInPlatformCompat(
                        callingPackage, userHandle)) {
            return true;
        }

        // Only check READ_PHONE_STATE for ACTIVE_DATA_SUBSCRIPTION_ID_CHANGED for Android 12
        // or above.
        if (events.contains(TelephonyCallback.EVENT_ACTIVE_DATA_SUBSCRIPTION_ID_CHANGED)
                && mConfigurationProvider.isActiveDataSubIdReadPhoneStateEnforcedInPlatformCompat(
                        callingPackage, userHandle)) {
            return true;
        }

        // Only check READ_PHONE_STATE for CELL_INFO_CHANGED for Android 12 or above.
        if (events.contains(TelephonyCallback.EVENT_CELL_INFO_CHANGED)
                && mConfigurationProvider.isCellInfoReadPhoneStateEnforcedInPlatformCompat(
                        callingPackage, userHandle)) {
            return true;
        }

        // Only check READ_PHONE_STATE for DISPLAY_INFO_CHANGED for Android 11 or older.
        // READ_PHONE_STATE is not required anymore after Android 12.
        if (events.contains(TelephonyCallback.EVENT_DISPLAY_INFO_CHANGED)
                && !mConfigurationProvider.isDisplayInfoReadPhoneStateEnforcedInPlatformCompat(
                        callingPackage, userHandle)) {
            return true;
        }

        return false;
    }

    private boolean isPrecisePhoneStatePermissionRequired(Set<Integer> events) {
        for (Integer requireEvent : REQUIRE_PRECISE_PHONE_STATE_PERMISSION) {
            if (events.contains(requireEvent)) {
                return true;
            }
        }
        return false;
    }

    private boolean isActiveEmergencySessionPermissionRequired(Set<Integer> events) {
        return events.contains(TelephonyCallback.EVENT_OUTGOING_EMERGENCY_CALL)
                || events.contains(TelephonyCallback.EVENT_OUTGOING_EMERGENCY_SMS);
    }

    private boolean isPrivilegedPhoneStatePermissionRequired(Set<Integer> events) {
        return events.contains(TelephonyCallback.EVENT_SRVCC_STATE_CHANGED)
                || events.contains(TelephonyCallback.EVENT_VOICE_ACTIVATION_STATE_CHANGED)
                || events.contains(TelephonyCallback.EVENT_RADIO_POWER_STATE_CHANGED)
                || events.contains(TelephonyCallback.EVENT_ALLOWED_NETWORK_TYPE_LIST_CHANGED);
    }

    private static final int MSG_USER_SWITCHED = 1;
    private static final int MSG_UPDATE_DEFAULT_SUB = 2;

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_USER_SWITCHED: {
                    if (VDBG) log("MSG_USER_SWITCHED userId=" + msg.arg1);
                    int numPhones = getTelephonyManager().getActiveModemCount();
                    for (int phoneId = 0; phoneId < numPhones; phoneId++) {
                        int[] subIds = SubscriptionManager.getSubId(phoneId);
                        int subId =
                                (subIds != null) && (subIds.length > 0)
                                        ? subIds[0]
                                        : SubscriptionManager.DEFAULT_SUBSCRIPTION_ID;
                        TelephonyRegistry.this.notifyCellLocationForSubscriber(
                                subId, mCellIdentity[phoneId], true /* hasUserSwitched */);
                    }
                    break;
                }
                case MSG_UPDATE_DEFAULT_SUB: {
                    int newDefaultPhoneId = msg.arg1;
                    int newDefaultSubId = msg.arg2;
                    if (VDBG) {
                        log("MSG_UPDATE_DEFAULT_SUB:current mDefaultSubId=" + mDefaultSubId
                                + " current mDefaultPhoneId=" + mDefaultPhoneId
                                + " newDefaultSubId=" + newDefaultSubId
                                + " newDefaultPhoneId=" + newDefaultPhoneId);
                    }

                    //Due to possible race condition,(notify call back using the new
                    //defaultSubId comes before new defaultSubId update) we need to recall all
                    //possible missed notify callback
                    synchronized (mRecords) {
                        for (Record r : mRecords) {
                            if(r.subId == SubscriptionManager.DEFAULT_SUBSCRIPTION_ID) {
                                checkPossibleMissNotify(r, newDefaultPhoneId);
                            }
                        }
                        handleRemoveListLocked();
                    }
                    mDefaultSubId = newDefaultSubId;
                    mDefaultPhoneId = newDefaultPhoneId;
                    mLocalLog.log("Default subscription updated: mDefaultPhoneId="
                            + mDefaultPhoneId + ", mDefaultSubId=" + mDefaultSubId);
                }
            }
        }
    };

    private class TelephonyRegistryDeathRecipient implements IBinder.DeathRecipient {

        private final IBinder binder;

        TelephonyRegistryDeathRecipient(IBinder binder) {
            this.binder = binder;
        }

        @Override
        public void binderDied() {
            if (DBG) log("binderDied " + binder);
            remove(binder);
        }
    }

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (VDBG) log("mBroadcastReceiver: action=" + action);
            if (Intent.ACTION_USER_SWITCHED.equals(action)) {
                int userHandle = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0);
                if (DBG) log("onReceive: userHandle=" + userHandle);
                mHandler.sendMessage(mHandler.obtainMessage(MSG_USER_SWITCHED, userHandle, 0));
            } else if (action.equals(SubscriptionManager.ACTION_DEFAULT_SUBSCRIPTION_CHANGED)) {
                int newDefaultSubId = intent.getIntExtra(
                        SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX,
                        SubscriptionManager.getDefaultSubscriptionId());
                int newDefaultPhoneId = intent.getIntExtra(
                        SubscriptionManager.EXTRA_SLOT_INDEX,
                        getPhoneIdFromSubId(newDefaultSubId));
                if (DBG) {
                    log("onReceive:current mDefaultSubId=" + mDefaultSubId
                            + " current mDefaultPhoneId=" + mDefaultPhoneId
                            + " newDefaultSubId=" + newDefaultSubId
                            + " newDefaultPhoneId=" + newDefaultPhoneId);
                }

                if (validatePhoneId(newDefaultPhoneId)
                        && (newDefaultSubId != mDefaultSubId
                                || newDefaultPhoneId != mDefaultPhoneId)) {
                    mHandler.sendMessage(mHandler.obtainMessage(MSG_UPDATE_DEFAULT_SUB,
                            newDefaultPhoneId, newDefaultSubId));
                }
            } else if (action.equals(ACTION_MULTI_SIM_CONFIG_CHANGED)) {
                onMultiSimConfigChanged();
            }
        }
    };

    private TelephonyManager getTelephonyManager() {
        return (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);
    }

    private void onMultiSimConfigChanged() {
        int oldNumPhones = mNumPhones;
        mNumPhones = getTelephonyManager().getActiveModemCount();
        if (oldNumPhones == mNumPhones) return;

        if (DBG) {
            log("TelephonyRegistry: activeModemCount changed from " + oldNumPhones
                    + " to " + mNumPhones);
        }
        mCallState = copyOf(mCallState, mNumPhones);
        mDataActivity = copyOf(mCallState, mNumPhones);
        mDataConnectionState = copyOf(mCallState, mNumPhones);
        mDataConnectionNetworkType = copyOf(mCallState, mNumPhones);
        mCallIncomingNumber = copyOf(mCallIncomingNumber, mNumPhones);
        mServiceState = copyOf(mServiceState, mNumPhones);
        mVoiceActivationState = copyOf(mVoiceActivationState, mNumPhones);
        mDataActivationState = copyOf(mDataActivationState, mNumPhones);
        mUserMobileDataState = copyOf(mUserMobileDataState, mNumPhones);
        if (mSignalStrength != null) {
            mSignalStrength = copyOf(mSignalStrength, mNumPhones);
        } else {
            mSignalStrength = new SignalStrength[mNumPhones];
        }
        mMessageWaiting = copyOf(mMessageWaiting, mNumPhones);
        mCallForwarding = copyOf(mCallForwarding, mNumPhones);
        mCellIdentity = copyOf(mCellIdentity, mNumPhones);
        mSrvccState = copyOf(mSrvccState, mNumPhones);
        mPreciseCallState = copyOf(mPreciseCallState, mNumPhones);
        mForegroundCallState = copyOf(mForegroundCallState, mNumPhones);
        mBackgroundCallState = copyOf(mBackgroundCallState, mNumPhones);
        mRingingCallState = copyOf(mRingingCallState, mNumPhones);
        mCallDisconnectCause = copyOf(mCallDisconnectCause, mNumPhones);
        mCallPreciseDisconnectCause = copyOf(mCallPreciseDisconnectCause, mNumPhones);
        mCallQuality = copyOf(mCallQuality, mNumPhones);
        mCallNetworkType = copyOf(mCallNetworkType, mNumPhones);
        mCallAttributes = copyOf(mCallAttributes, mNumPhones);
        mOutgoingCallEmergencyNumber = copyOf(mOutgoingCallEmergencyNumber, mNumPhones);
        mOutgoingSmsEmergencyNumber = copyOf(mOutgoingSmsEmergencyNumber, mNumPhones);
        mTelephonyDisplayInfos = copyOf(mTelephonyDisplayInfos, mNumPhones);
        mIsDataEnabled= copyOf(mIsDataEnabled, mNumPhones);
        mDataEnabledReason = copyOf(mDataEnabledReason, mNumPhones);
        mAllowedNetworkTypeReason = copyOf(mAllowedNetworkTypeReason, mNumPhones);
        mAllowedNetworkTypeValue = copyOf(mAllowedNetworkTypeValue, mNumPhones);

        // ds -> ss switch.
        if (mNumPhones < oldNumPhones) {
            cutListToSize(mCellInfo, mNumPhones);
            cutListToSize(mImsReasonInfo, mNumPhones);
            cutListToSize(mPreciseDataConnectionStates, mNumPhones);
            cutListToSize(mBarringInfo, mNumPhones);
            cutListToSize(mPhysicalChannelConfigs, mNumPhones);
            cutListToSize(mLinkCapacityEstimateLists, mNumPhones);
            return;
        }

        // mNumPhones > oldNumPhones: ss -> ds switch
        for (int i = oldNumPhones; i < mNumPhones; i++) {
            mCallState[i] =  TelephonyManager.CALL_STATE_IDLE;
            mDataActivity[i] = TelephonyManager.DATA_ACTIVITY_NONE;
            mDataConnectionState[i] = TelephonyManager.DATA_UNKNOWN;
            mVoiceActivationState[i] = TelephonyManager.SIM_ACTIVATION_STATE_UNKNOWN;
            mDataActivationState[i] = TelephonyManager.SIM_ACTIVATION_STATE_UNKNOWN;
            mCallIncomingNumber[i] =  "";
            mServiceState[i] =  new ServiceState();
            mSignalStrength[i] =  null;
            mUserMobileDataState[i] = false;
            mMessageWaiting[i] =  false;
            mCallForwarding[i] =  false;
            mCellIdentity[i] = null;
            mCellInfo.add(i, null);
            mImsReasonInfo.add(i, null);
            mSrvccState[i] = TelephonyManager.SRVCC_STATE_HANDOVER_NONE;
            mCallDisconnectCause[i] = DisconnectCause.NOT_VALID;
            mCallPreciseDisconnectCause[i] = PreciseDisconnectCause.NOT_VALID;
            mCallQuality[i] = createCallQuality();
            mCallAttributes[i] = new CallAttributes(createPreciseCallState(),
                    TelephonyManager.NETWORK_TYPE_UNKNOWN, createCallQuality());
            mCallNetworkType[i] = TelephonyManager.NETWORK_TYPE_UNKNOWN;
            mPreciseCallState[i] = createPreciseCallState();
            mRingingCallState[i] = PreciseCallState.PRECISE_CALL_STATE_IDLE;
            mForegroundCallState[i] = PreciseCallState.PRECISE_CALL_STATE_IDLE;
            mBackgroundCallState[i] = PreciseCallState.PRECISE_CALL_STATE_IDLE;
            mPreciseDataConnectionStates.add(new ArrayMap<>());
            mBarringInfo.add(i, new BarringInfo());
            mTelephonyDisplayInfos[i] = null;
            mIsDataEnabled[i] = false;
            mDataEnabledReason[i] = TelephonyManager.DATA_ENABLED_REASON_USER;
            mPhysicalChannelConfigs.add(i, new ArrayList<>());
            mAllowedNetworkTypeReason[i] = -1;
            mAllowedNetworkTypeValue[i] = -1;
            mLinkCapacityEstimateLists.add(i, new ArrayList<>());
        }
    }

    private void cutListToSize(List list, int size) {
        if (list == null) return;

        while (list.size() > size) {
            list.remove(list.size() - 1);
        }
    }

    // we keep a copy of all of the state so we can send it out when folks
    // register for it
    //
    // In these calls we call with the lock held. This is safe becasuse remote
    // calls go through a oneway interface and local calls going through a
    // handler before they get to app code.

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public TelephonyRegistry(Context context, ConfigurationProvider configurationProvider) {
        mContext = context;
        mConfigurationProvider = configurationProvider;
        mBatteryStats = BatteryStatsService.getService();

        int numPhones = getTelephonyManager().getActiveModemCount();
        if (DBG) log("TelephonyRegistry: ctor numPhones=" + numPhones);
        mNumPhones = numPhones;
        mCallState = new int[numPhones];
        mDataActivity = new int[numPhones];
        mDataConnectionState = new int[numPhones];
        mDataConnectionNetworkType = new int[numPhones];
        mCallIncomingNumber = new String[numPhones];
        mServiceState = new ServiceState[numPhones];
        mVoiceActivationState = new int[numPhones];
        mDataActivationState = new int[numPhones];
        mUserMobileDataState = new boolean[numPhones];
        mSignalStrength = new SignalStrength[numPhones];
        mMessageWaiting = new boolean[numPhones];
        mCallForwarding = new boolean[numPhones];
        mCellIdentity = new CellIdentity[numPhones];
        mSrvccState = new int[numPhones];
        mPreciseCallState = new PreciseCallState[numPhones];
        mForegroundCallState = new int[numPhones];
        mBackgroundCallState = new int[numPhones];
        mRingingCallState = new int[numPhones];
        mCallDisconnectCause = new int[numPhones];
        mCallPreciseDisconnectCause = new int[numPhones];
        mCallQuality = new CallQuality[numPhones];
        mCallNetworkType = new int[numPhones];
        mCallAttributes = new CallAttributes[numPhones];
        mPreciseDataConnectionStates = new ArrayList<>();
        mCellInfo = new ArrayList<>();
        mImsReasonInfo = new ArrayList<>();
        mEmergencyNumberList = new HashMap<>();
        mOutgoingCallEmergencyNumber = new EmergencyNumber[numPhones];
        mOutgoingSmsEmergencyNumber = new EmergencyNumber[numPhones];
        mBarringInfo = new ArrayList<>();
        mTelephonyDisplayInfos = new TelephonyDisplayInfo[numPhones];
        mPhysicalChannelConfigs = new ArrayList<>();
        mAllowedNetworkTypeReason = new int[numPhones];
        mAllowedNetworkTypeValue = new long[numPhones];
        mIsDataEnabled = new boolean[numPhones];
        mDataEnabledReason = new int[numPhones];
        mLinkCapacityEstimateLists = new ArrayList<>();

        for (int i = 0; i < numPhones; i++) {
            mCallState[i] =  TelephonyManager.CALL_STATE_IDLE;
            mDataActivity[i] = TelephonyManager.DATA_ACTIVITY_NONE;
            mDataConnectionState[i] = TelephonyManager.DATA_UNKNOWN;
            mVoiceActivationState[i] = TelephonyManager.SIM_ACTIVATION_STATE_UNKNOWN;
            mDataActivationState[i] = TelephonyManager.SIM_ACTIVATION_STATE_UNKNOWN;
            mCallIncomingNumber[i] =  "";
            mServiceState[i] =  new ServiceState();
            mSignalStrength[i] =  null;
            mUserMobileDataState[i] = false;
            mMessageWaiting[i] =  false;
            mCallForwarding[i] =  false;
            mCellIdentity[i] = null;
            mCellInfo.add(i, null);
            mImsReasonInfo.add(i, null);
            mSrvccState[i] = TelephonyManager.SRVCC_STATE_HANDOVER_NONE;
            mCallDisconnectCause[i] = DisconnectCause.NOT_VALID;
            mCallPreciseDisconnectCause[i] = PreciseDisconnectCause.NOT_VALID;
            mCallQuality[i] = createCallQuality();
            mCallAttributes[i] = new CallAttributes(createPreciseCallState(),
                    TelephonyManager.NETWORK_TYPE_UNKNOWN, createCallQuality());
            mCallNetworkType[i] = TelephonyManager.NETWORK_TYPE_UNKNOWN;
            mPreciseCallState[i] = createPreciseCallState();
            mRingingCallState[i] = PreciseCallState.PRECISE_CALL_STATE_IDLE;
            mForegroundCallState[i] = PreciseCallState.PRECISE_CALL_STATE_IDLE;
            mBackgroundCallState[i] = PreciseCallState.PRECISE_CALL_STATE_IDLE;
            mPreciseDataConnectionStates.add(new ArrayMap<>());
            mBarringInfo.add(i, new BarringInfo());
            mTelephonyDisplayInfos[i] = null;
            mIsDataEnabled[i] = false;
            mDataEnabledReason[i] = TelephonyManager.DATA_ENABLED_REASON_USER;
            mPhysicalChannelConfigs.add(i, new ArrayList<>());
            mAllowedNetworkTypeReason[i] = -1;
            mAllowedNetworkTypeValue[i] = -1;
            mLinkCapacityEstimateLists.add(i, new ArrayList<>());
        }

        mAppOps = mContext.getSystemService(AppOpsManager.class);
    }

    public void systemRunning() {
        // Watch for interesting updates
        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        filter.addAction(Intent.ACTION_USER_REMOVED);
        filter.addAction(SubscriptionManager.ACTION_DEFAULT_SUBSCRIPTION_CHANGED);
        filter.addAction(ACTION_MULTI_SIM_CONFIG_CHANGED);
        log("systemRunning register for intents");
        mContext.registerReceiver(mBroadcastReceiver, filter);
    }

    @Override
    public void addOnSubscriptionsChangedListener(String callingPackage, String callingFeatureId,
            IOnSubscriptionsChangedListener callback) {
        int callerUserId = UserHandle.getCallingUserId();
        mAppOps.checkPackage(Binder.getCallingUid(), callingPackage);
        if (VDBG) {
            log("listen oscl: E pkg=" + pii(callingPackage) + " uid=" + Binder.getCallingUid()
                    + " myUserId=" + UserHandle.myUserId() + " callerUserId=" + callerUserId
                    + " callback=" + callback + " callback.asBinder=" + callback.asBinder());
        }

        synchronized (mRecords) {
            // register
            IBinder b = callback.asBinder();
            Record r = add(b, Binder.getCallingUid(), Binder.getCallingPid(), false);

            if (r == null) {
                return;
            }

            r.context = mContext;
            r.onSubscriptionsChangedListenerCallback = callback;
            r.callingPackage = callingPackage;
            r.callingFeatureId = callingFeatureId;
            r.callerUid = Binder.getCallingUid();
            r.callerPid = Binder.getCallingPid();
            r.eventList = new ArraySet<>();
            if (DBG) {
                log("listen oscl:  Register r=" + r);
            }
            // Always notify when registration occurs if there has been a notification.
            if (mHasNotifySubscriptionInfoChangedOccurred) {
                try {
                    if (VDBG) log("listen oscl: send to r=" + r);
                    r.onSubscriptionsChangedListenerCallback.onSubscriptionsChanged();
                    if (VDBG) log("listen oscl: sent to r=" + r);
                } catch (RemoteException e) {
                    if (VDBG) log("listen oscl: remote exception sending to r=" + r + " e=" + e);
                    remove(r.binder);
                }
            } else {
                log("listen oscl: mHasNotifySubscriptionInfoChangedOccurred==false no callback");
            }
        }
    }

    @Override
    public void removeOnSubscriptionsChangedListener(String pkgForDebug,
            IOnSubscriptionsChangedListener callback) {
        if (DBG) log("listen oscl: Unregister");
        remove(callback.asBinder());
    }


    @Override
    public void addOnOpportunisticSubscriptionsChangedListener(String callingPackage,
            String callingFeatureId, IOnSubscriptionsChangedListener callback) {
        int callerUserId = UserHandle.getCallingUserId();
        mAppOps.checkPackage(Binder.getCallingUid(), callingPackage);
        if (VDBG) {
            log("listen ooscl: E pkg=" + pii(callingPackage) + " uid=" + Binder.getCallingUid()
                    + " myUserId=" + UserHandle.myUserId() + " callerUserId=" + callerUserId
                    + " callback=" + callback + " callback.asBinder=" + callback.asBinder());
        }

        synchronized (mRecords) {
            // register
            IBinder b = callback.asBinder();
            Record r = add(b, Binder.getCallingUid(), Binder.getCallingPid(), false);

            if (r == null) {
                return;
            }

            r.context = mContext;
            r.onOpportunisticSubscriptionsChangedListenerCallback = callback;
            r.callingPackage = callingPackage;
            r.callingFeatureId = callingFeatureId;
            r.callerUid = Binder.getCallingUid();
            r.callerPid = Binder.getCallingPid();
            r.eventList = new ArraySet<>();
            if (DBG) {
                log("listen ooscl:  Register r=" + r);
            }
            // Always notify when registration occurs if there has been a notification.
            if (mHasNotifyOpportunisticSubscriptionInfoChangedOccurred) {
                try {
                    if (VDBG) log("listen ooscl: send to r=" + r);
                    r.onOpportunisticSubscriptionsChangedListenerCallback.onSubscriptionsChanged();
                    if (VDBG) log("listen ooscl: sent to r=" + r);
                } catch (RemoteException e) {
                    if (VDBG) log("listen ooscl: remote exception sending to r=" + r + " e=" + e);
                    remove(r.binder);
                }
            } else {
                log("listen ooscl: hasNotifyOpptSubInfoChangedOccurred==false no callback");
            }
        }
    }

    @Override
    public void notifySubscriptionInfoChanged() {
        if (VDBG) log("notifySubscriptionInfoChanged:");
        synchronized (mRecords) {
            if (!mHasNotifySubscriptionInfoChangedOccurred) {
                log("notifySubscriptionInfoChanged: first invocation mRecords.size="
                        + mRecords.size());
            }
            mHasNotifySubscriptionInfoChangedOccurred = true;
            mRemoveList.clear();
            for (Record r : mRecords) {
                if (r.matchOnSubscriptionsChangedListener()) {
                    try {
                        if (VDBG) log("notifySubscriptionInfoChanged: call osc to r=" + r);
                        r.onSubscriptionsChangedListenerCallback.onSubscriptionsChanged();
                        if (VDBG) log("notifySubscriptionInfoChanged: done osc to r=" + r);
                    } catch (RemoteException ex) {
                        if (VDBG) log("notifySubscriptionInfoChanged: RemoteException r=" + r);
                        mRemoveList.add(r.binder);
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    @Override
    public void notifyOpportunisticSubscriptionInfoChanged() {
        if (VDBG) log("notifyOpptSubscriptionInfoChanged:");
        synchronized (mRecords) {
            if (!mHasNotifyOpportunisticSubscriptionInfoChangedOccurred) {
                log("notifyOpptSubscriptionInfoChanged: first invocation mRecords.size="
                        + mRecords.size());
            }
            mHasNotifyOpportunisticSubscriptionInfoChangedOccurred = true;
            mRemoveList.clear();
            for (Record r : mRecords) {
                if (r.matchOnOpportunisticSubscriptionsChangedListener()) {
                    try {
                        if (VDBG) log("notifyOpptSubChanged: call oosc to r=" + r);
                        r.onOpportunisticSubscriptionsChangedListenerCallback
                                .onSubscriptionsChanged();
                        if (VDBG) log("notifyOpptSubChanged: done oosc to r=" + r);
                    } catch (RemoteException ex) {
                        if (VDBG) log("notifyOpptSubChanged: RemoteException r=" + r);
                        mRemoveList.add(r.binder);
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    @Override
    public void listenWithEventList(int subId, String callingPackage, String callingFeatureId,
            IPhoneStateListener callback, int[] events, boolean notifyNow) {
        Set<Integer> eventList = Arrays.stream(events).boxed().collect(Collectors.toSet());
        listen(callingPackage, callingFeatureId, callback, eventList, notifyNow, subId);
    }

    private void listen(String callingPackage, @Nullable String callingFeatureId,
            IPhoneStateListener callback, Set<Integer> events, boolean notifyNow, int subId) {
        int callerUserId = UserHandle.getCallingUserId();
        mAppOps.checkPackage(Binder.getCallingUid(), callingPackage);
        String str = "listen: E pkg=" + pii(callingPackage) + " uid=" + Binder.getCallingUid()
                + " events=" + events + " notifyNow=" + notifyNow
                + " subId=" + subId + " myUserId=" + UserHandle.myUserId()
                + " callerUserId=" + callerUserId;
        mListenLog.log(str);
        if (VDBG) {
            log(str);
        }

        if (events.isEmpty()) {
            if (DBG) {
                log("listen: Unregister");
            }
            events.clear();
            remove(callback.asBinder());
            return;
        }

        // Checks permission and throws SecurityException for disallowed operations. For pre-M
        // apps whose runtime permission has been revoked, we return immediately to skip sending
        // events to the app without crashing it.
        if (!checkListenerPermission(events, subId, callingPackage, callingFeatureId, "listen")) {
            return;
        }

        int phoneId = getPhoneIdFromSubId(subId);
        synchronized (mRecords) {
            // register
            IBinder b = callback.asBinder();
            boolean doesLimitApply =
                    Binder.getCallingUid() != Process.SYSTEM_UID
                            && Binder.getCallingUid() != Process.PHONE_UID
                            && Binder.getCallingUid() != Process.myUid();
            Record r = add(b, Binder.getCallingUid(), Binder.getCallingPid(), doesLimitApply);

            if (r == null) {
                return;
            }

            r.context = mContext;
            r.callback = callback;
            r.callingPackage = callingPackage;
            r.callingFeatureId = callingFeatureId;
            r.callerUid = Binder.getCallingUid();
            r.callerPid = Binder.getCallingPid();
            // Legacy applications pass SubscriptionManager.DEFAULT_SUB_ID,
            // force all illegal subId to SubscriptionManager.DEFAULT_SUB_ID
            if (!SubscriptionManager.isValidSubscriptionId(subId)) {
                r.subId = SubscriptionManager.DEFAULT_SUBSCRIPTION_ID;
            } else {//APP specify subID
                r.subId = subId;
            }
            r.phoneId = phoneId;
            r.eventList = events;

            if (DBG) {
                log("listen:  Register r=" + r + " r.subId=" + r.subId + " phoneId=" + phoneId);
            }
            if (notifyNow && validatePhoneId(phoneId)) {
                if (events.contains(TelephonyCallback.EVENT_SERVICE_STATE_CHANGED)){
                    try {
                        if (VDBG) log("listen: call onSSC state=" + mServiceState[phoneId]);
                        ServiceState rawSs = new ServiceState(mServiceState[phoneId]);
                        if (checkFineLocationAccess(r, Build.VERSION_CODES.Q)) {
                            r.callback.onServiceStateChanged(rawSs);
                        } else if (checkCoarseLocationAccess(r, Build.VERSION_CODES.Q)) {
                            r.callback.onServiceStateChanged(
                                    rawSs.createLocationInfoSanitizedCopy(false));
                        } else {
                            r.callback.onServiceStateChanged(
                                    rawSs.createLocationInfoSanitizedCopy(true));
                        }
                    } catch (RemoteException ex) {
                        remove(r.binder);
                    }
                }
                if (events.contains(TelephonyCallback.EVENT_SIGNAL_STRENGTH_CHANGED)) {
                    try {
                        if (mSignalStrength[phoneId] != null) {
                            int gsmSignalStrength = mSignalStrength[phoneId]
                                    .getGsmSignalStrength();
                            r.callback.onSignalStrengthChanged((gsmSignalStrength == 99 ? -1
                                    : gsmSignalStrength));
                        }
                    } catch (RemoteException ex) {
                        remove(r.binder);
                    }
                }
                if (events.contains(
                        TelephonyCallback.EVENT_MESSAGE_WAITING_INDICATOR_CHANGED)) {
                    try {
                        r.callback.onMessageWaitingIndicatorChanged(
                                mMessageWaiting[phoneId]);
                    } catch (RemoteException ex) {
                        remove(r.binder);
                    }
                }
                if (events.contains(
                        TelephonyCallback.EVENT_CALL_FORWARDING_INDICATOR_CHANGED)) {
                    try {
                        r.callback.onCallForwardingIndicatorChanged(
                                mCallForwarding[phoneId]);
                    } catch (RemoteException ex) {
                        remove(r.binder);
                    }
                }
                if (validateEventAndUserLocked(
                        r, TelephonyCallback.EVENT_CELL_LOCATION_CHANGED)) {
                    try {
                        if (DBG_LOC) log("listen: mCellIdentity = " + mCellIdentity[phoneId]);
                        if (checkCoarseLocationAccess(r, Build.VERSION_CODES.BASE)
                                && checkFineLocationAccess(r, Build.VERSION_CODES.Q)) {
                            // null will be translated to empty CellLocation object in client.
                            r.callback.onCellLocationChanged(mCellIdentity[phoneId]);
                        }
                    } catch (RemoteException ex) {
                        remove(r.binder);
                    }
                }
                if (events.contains(TelephonyCallback.EVENT_LEGACY_CALL_STATE_CHANGED)) {
                    try {
                        r.callback.onLegacyCallStateChanged(mCallState[phoneId],
                                getCallIncomingNumber(r, phoneId));
                    } catch (RemoteException ex) {
                        remove(r.binder);
                    }
                }
                if (events.contains(TelephonyCallback.EVENT_CALL_STATE_CHANGED)) {
                    try {
                        r.callback.onCallStateChanged(mCallState[phoneId]);
                    } catch (RemoteException ex) {
                        remove(r.binder);
                    }
                }
                if (events.contains(TelephonyCallback.EVENT_DATA_CONNECTION_STATE_CHANGED)) {
                    try {
                        r.callback.onDataConnectionStateChanged(mDataConnectionState[phoneId],
                                mDataConnectionNetworkType[phoneId]);
                    } catch (RemoteException ex) {
                        remove(r.binder);
                    }
                }
                if (events.contains(TelephonyCallback.EVENT_DATA_ACTIVITY_CHANGED)) {
                    try {
                        r.callback.onDataActivity(mDataActivity[phoneId]);
                    } catch (RemoteException ex) {
                        remove(r.binder);
                    }
                }
                if (events.contains(TelephonyCallback.EVENT_SIGNAL_STRENGTHS_CHANGED)) {
                    try {
                        if (mSignalStrength[phoneId] != null) {
                            r.callback.onSignalStrengthsChanged(mSignalStrength[phoneId]);
                        }
                    } catch (RemoteException ex) {
                        remove(r.binder);
                    }
                }
                if (events.contains(
                        TelephonyCallback.EVENT_ALWAYS_REPORTED_SIGNAL_STRENGTH_CHANGED)) {
                    updateReportSignalStrengthDecision(r.subId);
                    try {
                        if (mSignalStrength[phoneId] != null) {
                            r.callback.onSignalStrengthsChanged(mSignalStrength[phoneId]);
                        }
                    } catch (RemoteException ex) {
                        remove(r.binder);
                    }
                }
                if (validateEventAndUserLocked(
                        r, TelephonyCallback.EVENT_CELL_INFO_CHANGED)) {
                    try {
                        if (DBG_LOC) log("listen: mCellInfo[" + phoneId + "] = "
                                + mCellInfo.get(phoneId));
                        if (checkCoarseLocationAccess(r, Build.VERSION_CODES.BASE)
                                && checkFineLocationAccess(r, Build.VERSION_CODES.Q)) {
                            r.callback.onCellInfoChanged(mCellInfo.get(phoneId));
                        }
                    } catch (RemoteException ex) {
                        remove(r.binder);
                    }
                }
                if (events.contains(TelephonyCallback.EVENT_PRECISE_CALL_STATE_CHANGED)) {
                    try {
                        r.callback.onPreciseCallStateChanged(mPreciseCallState[phoneId]);
                    } catch (RemoteException ex) {
                        remove(r.binder);
                    }
                }
                if (events.contains(TelephonyCallback.EVENT_CALL_DISCONNECT_CAUSE_CHANGED)) {
                    try {
                        r.callback.onCallDisconnectCauseChanged(mCallDisconnectCause[phoneId],
                                mCallPreciseDisconnectCause[phoneId]);
                    } catch (RemoteException ex) {
                        remove(r.binder);
                    }
                }
                if (events.contains(TelephonyCallback.EVENT_IMS_CALL_DISCONNECT_CAUSE_CHANGED)) {
                    try {
                        r.callback.onImsCallDisconnectCauseChanged(mImsReasonInfo.get(phoneId));
                    } catch (RemoteException ex) {
                        remove(r.binder);
                    }
                }
                if (events.contains(
                        TelephonyCallback.EVENT_PRECISE_DATA_CONNECTION_STATE_CHANGED)) {
                    try {
                        for (PreciseDataConnectionState pdcs
                                : mPreciseDataConnectionStates.get(phoneId).values()) {
                            r.callback.onPreciseDataConnectionStateChanged(pdcs);
                        }
                    } catch (RemoteException ex) {
                        remove(r.binder);
                    }
                }
                if (events.contains(TelephonyCallback.EVENT_CARRIER_NETWORK_CHANGED)) {
                    try {
                        r.callback.onCarrierNetworkChange(mCarrierNetworkChangeState);
                    } catch (RemoteException ex) {
                        remove(r.binder);
                    }
                }
                if (events.contains(TelephonyCallback.EVENT_VOICE_ACTIVATION_STATE_CHANGED)) {
                    try {
                        r.callback.onVoiceActivationStateChanged(
                                mVoiceActivationState[phoneId]);
                    } catch (RemoteException ex) {
                        remove(r.binder);
                    }
                }
                if (events.contains(TelephonyCallback.EVENT_DATA_ACTIVATION_STATE_CHANGED)) {
                    try {
                        r.callback.onDataActivationStateChanged(mDataActivationState[phoneId]);
                    } catch (RemoteException ex) {
                        remove(r.binder);
                    }
                }
                if (events.contains(TelephonyCallback.EVENT_USER_MOBILE_DATA_STATE_CHANGED)) {
                    try {
                        r.callback.onUserMobileDataStateChanged(mUserMobileDataState[phoneId]);
                    } catch (RemoteException ex) {
                        remove(r.binder);
                    }
                }
                if (events.contains(TelephonyCallback.EVENT_DISPLAY_INFO_CHANGED)) {
                    try {
                        if (mTelephonyDisplayInfos[phoneId] != null) {
                            r.callback.onDisplayInfoChanged(mTelephonyDisplayInfos[phoneId]);
                        }
                    } catch (RemoteException ex) {
                        remove(r.binder);
                    }
                }
                if (events.contains(TelephonyCallback.EVENT_EMERGENCY_NUMBER_LIST_CHANGED)) {
                    try {
                        r.callback.onEmergencyNumberListChanged(mEmergencyNumberList);
                    } catch (RemoteException ex) {
                        remove(r.binder);
                    }
                }
                if (events.contains(TelephonyCallback.EVENT_PHONE_CAPABILITY_CHANGED)) {
                    try {
                        r.callback.onPhoneCapabilityChanged(mPhoneCapability);
                    } catch (RemoteException ex) {
                        remove(r.binder);
                    }
                }
                if (events.contains(
                        TelephonyCallback.EVENT_ACTIVE_DATA_SUBSCRIPTION_ID_CHANGED)) {
                    try {
                        r.callback.onActiveDataSubIdChanged(mActiveDataSubId);
                    } catch (RemoteException ex) {
                        remove(r.binder);
                    }
                }
                if (events.contains(TelephonyCallback.EVENT_RADIO_POWER_STATE_CHANGED)) {
                    try {
                        r.callback.onRadioPowerStateChanged(mRadioPowerState);
                    } catch (RemoteException ex) {
                        remove(r.binder);
                    }
                }
                if (events.contains(TelephonyCallback.EVENT_SRVCC_STATE_CHANGED)) {
                    try {
                        r.callback.onSrvccStateChanged(mSrvccState[phoneId]);
                    } catch (RemoteException ex) {
                        remove(r.binder);
                    }
                }
                if (events.contains(TelephonyCallback.EVENT_CALL_ATTRIBUTES_CHANGED)) {
                    try {
                        r.callback.onCallAttributesChanged(mCallAttributes[phoneId]);
                    } catch (RemoteException ex) {
                        remove(r.binder);
                    }
                }
                if (events.contains(TelephonyCallback.EVENT_BARRING_INFO_CHANGED)) {
                    BarringInfo barringInfo = mBarringInfo.get(phoneId);
                    BarringInfo biNoLocation = barringInfo != null
                            ? barringInfo.createLocationInfoSanitizedCopy() : null;
                    if (VDBG) log("listen: call onBarringInfoChanged=" + barringInfo);
                    try {
                        r.callback.onBarringInfoChanged(
                                checkFineLocationAccess(r, Build.VERSION_CODES.BASE)
                                        ? barringInfo : biNoLocation);
                    } catch (RemoteException ex) {
                        remove(r.binder);
                    }
                }
                if (events.contains(
                        TelephonyCallback.EVENT_PHYSICAL_CHANNEL_CONFIG_CHANGED)) {
                    try {
                        r.callback.onPhysicalChannelConfigChanged(
                                shouldSanitizeLocationForPhysicalChannelConfig(r)
                                        ? getLocationSanitizedConfigs(
                                                mPhysicalChannelConfigs.get(phoneId))
                                        : mPhysicalChannelConfigs.get(phoneId));
                    } catch (RemoteException ex) {
                        remove(r.binder);
                    }
                }
                if (events.contains(
                        TelephonyCallback.EVENT_DATA_ENABLED_CHANGED)) {
                    try {
                        r.callback.onDataEnabledChanged(
                                mIsDataEnabled[phoneId], mDataEnabledReason[phoneId]);
                    } catch (RemoteException ex) {
                        remove(r.binder);
                    }
                }
                if (events.contains(
                        TelephonyCallback.EVENT_LINK_CAPACITY_ESTIMATE_CHANGED)) {
                    try {
                        if (mLinkCapacityEstimateLists.get(phoneId) != null) {
                            r.callback.onLinkCapacityEstimateChanged(mLinkCapacityEstimateLists
                                    .get(phoneId));
                        }
                    } catch (RemoteException ex) {
                        remove(r.binder);
                    }
                }
            }
        }
    }

    private void updateReportSignalStrengthDecision(int subscriptionId) {
        synchronized (mRecords) {
            TelephonyManager telephonyManager = (TelephonyManager) mContext
                    .getSystemService(Context.TELEPHONY_SERVICE);
            for (Record r : mRecords) {
                // If any of the system clients wants to always listen to signal strength,
                // we need to set it on.
                if (r.matchTelephonyCallbackEvent(
                        TelephonyCallback.EVENT_ALWAYS_REPORTED_SIGNAL_STRENGTH_CHANGED)) {
                    telephonyManager.createForSubscriptionId(subscriptionId)
                            .setAlwaysReportSignalStrength(true);
                    return;
                }
            }
            // If none of the system clients wants to always listen to signal strength,
            // we need to set it off.
            telephonyManager.createForSubscriptionId(subscriptionId)
                    .setAlwaysReportSignalStrength(false);
        }
    }

    private String getCallIncomingNumber(Record record, int phoneId) {
        // Only reveal the incoming number if the record has read call log permission.
        return record.canReadCallLog() ? mCallIncomingNumber[phoneId] : "";
    }

    private Record add(IBinder binder, int callingUid, int callingPid, boolean doesLimitApply) {
        Record r;

        synchronized (mRecords) {
            final int N = mRecords.size();
            // While iterating through the records, keep track of how many we have from this pid.
            int numRecordsForPid = 0;
            for (int i = 0; i < N; i++) {
                r = mRecords.get(i);
                if (binder == r.binder) {
                    // Already existed.
                    return r;
                }
                if (r.callerPid == callingPid) {
                    numRecordsForPid++;
                }
            }
            // If we've exceeded the limit for registrations, log an error and quit.
            int registrationLimit = mConfigurationProvider.getRegistrationLimit();

            if (doesLimitApply
                    && registrationLimit >= 1
                    && numRecordsForPid >= registrationLimit) {
                String errorMsg = "Pid " + callingPid + " has exceeded the number of permissible"
                        + " registered listeners. Ignoring request to add.";
                loge(errorMsg);
                if (mConfigurationProvider
                        .isRegistrationLimitEnabledInPlatformCompat(callingUid)) {
                    throw new IllegalStateException(errorMsg);
                }
            } else if (doesLimitApply && numRecordsForPid
                    >= TelephonyCallback.DEFAULT_PER_PID_REGISTRATION_LIMIT / 2) {
                // Log the warning independently of the dynamically set limit -- apps shouldn't be
                // doing this regardless of whether we're throwing them an exception for it.
                Rlog.w(TAG, "Pid " + callingPid + " has exceeded half the number of permissible"
                        + " registered listeners. Now at " + numRecordsForPid);
            }

            r = new Record();
            r.binder = binder;
            r.deathRecipient = new TelephonyRegistryDeathRecipient(binder);

            try {
                binder.linkToDeath(r.deathRecipient, 0);
            } catch (RemoteException e) {
                if (VDBG) log("LinkToDeath remote exception sending to r=" + r + " e=" + e);
                // Binder already died. Return null.
                return null;
            }

            mRecords.add(r);
            if (DBG) log("add new record");
        }

        return r;
    }

    private void remove(IBinder binder) {
        synchronized (mRecords) {
            final int recordCount = mRecords.size();
            for (int i = 0; i < recordCount; i++) {
                Record r = mRecords.get(i);
                if (r.binder == binder) {
                    if (DBG) {
                        log("remove: binder=" + binder + " r.callingPackage " + r.callingPackage
                                + " r.callback " + r.callback);
                    }

                    if (r.deathRecipient != null) {
                        try {
                            binder.unlinkToDeath(r.deathRecipient, 0);
                        } catch (NoSuchElementException e) {
                            if (VDBG) log("UnlinkToDeath NoSuchElementException sending to r="
                                    + r + " e=" + e);
                        }
                    }

                    mRecords.remove(i);

                    // Every time a client that is registrating to always receive the signal
                    // strength is removed from registry records, we need to check if
                    // the signal strength decision needs to update on its slot.
                    if (r.matchTelephonyCallbackEvent(
                            TelephonyCallback.EVENT_ALWAYS_REPORTED_SIGNAL_STRENGTH_CHANGED)) {
                        updateReportSignalStrengthDecision(r.subId);
                    }
                    return;
                }
            }
        }
    }

    public void notifyCallStateForAllSubs(int state, String phoneNumber) {
        if (!checkNotifyPermission("notifyCallState()")) {
            return;
        }

        if (VDBG) {
            log("notifyCallStateForAllSubs: state=" + state + " phoneNumber=" + phoneNumber);
        }

        synchronized (mRecords) {
            for (Record r : mRecords) {
                if (r.matchTelephonyCallbackEvent(TelephonyCallback.EVENT_LEGACY_CALL_STATE_CHANGED)
                        && (r.subId == SubscriptionManager.DEFAULT_SUBSCRIPTION_ID)) {
                    try {
                        // Ensure the listener has read call log permission; if they do not return
                        // an empty phone number.
                        // This is ONLY for legacy onCallStateChanged in PhoneStateListener.
                        String phoneNumberOrEmpty = r.canReadCallLog() ? phoneNumber : "";
                        r.callback.onLegacyCallStateChanged(state, phoneNumberOrEmpty);
                    } catch (RemoteException ex) {
                        mRemoveList.add(r.binder);
                    }
                }

                if (r.matchTelephonyCallbackEvent(TelephonyCallback.EVENT_CALL_STATE_CHANGED)
                        && (r.subId == SubscriptionManager.DEFAULT_SUBSCRIPTION_ID)) {
                    try {
                        // The new callback does NOT provide the phone number.
                        r.callback.onCallStateChanged(state);
                    } catch (RemoteException ex) {
                        mRemoveList.add(r.binder);
                    }
                }
            }
            handleRemoveListLocked();
        }

        // Called only by Telecomm to communicate call state across different phone accounts. So
        // there is no need to add a valid subId or slotId.
        broadcastCallStateChanged(state, phoneNumber,
                SubscriptionManager.INVALID_SIM_SLOT_INDEX,
                SubscriptionManager.INVALID_SUBSCRIPTION_ID);
    }

    public void notifyCallState(int phoneId, int subId, int state, String incomingNumber) {
        if (!checkNotifyPermission("notifyCallState()")) {
            return;
        }
        if (VDBG) {
            log("notifyCallState: subId=" + subId
                + " state=" + state + " incomingNumber=" + incomingNumber);
        }
        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                mCallState[phoneId] = state;
                mCallIncomingNumber[phoneId] = incomingNumber;
                for (Record r : mRecords) {
                    if (r.matchTelephonyCallbackEvent(
                            TelephonyCallback.EVENT_LEGACY_CALL_STATE_CHANGED)
                            && (r.subId == subId)
                            && (r.subId != SubscriptionManager.DEFAULT_SUBSCRIPTION_ID)) {
                        try {
                            // Only the legacy PhoneStateListener receives the phone number.
                            String incomingNumberOrEmpty = getCallIncomingNumber(r, phoneId);
                            r.callback.onLegacyCallStateChanged(state, incomingNumberOrEmpty);
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                    if (r.matchTelephonyCallbackEvent(TelephonyCallback.EVENT_CALL_STATE_CHANGED)
                            && (r.subId == subId)
                            && (r.subId != SubscriptionManager.DEFAULT_SUBSCRIPTION_ID)) {
                        try {
                            // The phone number is not included in the new call state changed
                            // listener.
                            r.callback.onCallStateChanged(state);
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
        broadcastCallStateChanged(state, incomingNumber, phoneId, subId);
    }

    public void notifyServiceStateForPhoneId(int phoneId, int subId, ServiceState state) {
        if (!checkNotifyPermission("notifyServiceState()")){
            return;
        }

        synchronized (mRecords) {
            String str = "notifyServiceStateForSubscriber: subId=" + subId + " phoneId=" + phoneId
                    + " state=" + state;
            if (VDBG) {
                log(str);
            }
            mLocalLog.log(str);
            // for service state updates, don't notify clients when subId is invalid. This prevents
            // us from sending incorrect notifications like b/133140128
            // In the future, we can remove this logic for every notification here and add a
            // callback so listeners know when their PhoneStateListener's subId becomes invalid, but
            // for now we use the simplest fix.
            if (validatePhoneId(phoneId) && SubscriptionManager.isValidSubscriptionId(subId)) {
                mServiceState[phoneId] = state;

                for (Record r : mRecords) {
                    if (VDBG) {
                        log("notifyServiceStateForSubscriber: r=" + r + " subId=" + subId
                                + " phoneId=" + phoneId + " state=" + state);
                    }
                    if (r.matchTelephonyCallbackEvent(
                            TelephonyCallback.EVENT_SERVICE_STATE_CHANGED)
                            && idMatch(r.subId, subId, phoneId)) {

                        try {
                            ServiceState stateToSend;
                            if (checkFineLocationAccess(r, Build.VERSION_CODES.Q)) {
                                stateToSend = new ServiceState(state);
                            } else if (checkCoarseLocationAccess(r, Build.VERSION_CODES.Q)) {
                                stateToSend = state.createLocationInfoSanitizedCopy(false);
                            } else {
                                stateToSend = state.createLocationInfoSanitizedCopy(true);
                            }
                            if (DBG) {
                                log("notifyServiceStateForSubscriber: callback.onSSC r=" + r
                                        + " subId=" + subId + " phoneId=" + phoneId
                                        + " state=" + state);
                            }
                            r.callback.onServiceStateChanged(stateToSend);
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }
            } else {
                log("notifyServiceStateForSubscriber: INVALID phoneId=" + phoneId
                        + " or subId=" + subId);
            }
            handleRemoveListLocked();
        }
        broadcastServiceStateChanged(state, phoneId, subId);
    }

    public void notifySimActivationStateChangedForPhoneId(int phoneId, int subId,
            int activationType, int activationState) {
        if (!checkNotifyPermission("notifySimActivationState()")){
            return;
        }
        if (VDBG) {
            log("notifySimActivationStateForPhoneId: subId=" + subId + " phoneId=" + phoneId
                    + "type=" + activationType + " state=" + activationState);
        }
        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                switch (activationType) {
                    case SIM_ACTIVATION_TYPE_VOICE:
                        mVoiceActivationState[phoneId] = activationState;
                        break;
                    case SIM_ACTIVATION_TYPE_DATA:
                        mDataActivationState[phoneId] = activationState;
                        break;
                    default:
                        return;
                }
                for (Record r : mRecords) {
                    if (VDBG) {
                        log("notifySimActivationStateForPhoneId: r=" + r + " subId=" + subId
                                + " phoneId=" + phoneId + "type=" + activationType
                                + " state=" + activationState);
                    }
                    try {
                        if ((activationType == SIM_ACTIVATION_TYPE_VOICE)
                                && r.matchTelephonyCallbackEvent(
                                        TelephonyCallback.EVENT_VOICE_ACTIVATION_STATE_CHANGED)
                                && idMatch(r.subId, subId, phoneId)) {
                            if (DBG) {
                                log("notifyVoiceActivationStateForPhoneId: callback.onVASC r=" + r
                                        + " subId=" + subId + " phoneId=" + phoneId
                                        + " state=" + activationState);
                            }
                            r.callback.onVoiceActivationStateChanged(activationState);
                        }
                        if ((activationType == SIM_ACTIVATION_TYPE_DATA)
                                && r.matchTelephonyCallbackEvent(
                                        TelephonyCallback.EVENT_DATA_ACTIVATION_STATE_CHANGED)
                                && idMatch(r.subId, subId, phoneId)) {
                            if (DBG) {
                                log("notifyDataActivationStateForPhoneId: callback.onDASC r=" + r
                                        + " subId=" + subId + " phoneId=" + phoneId
                                        + " state=" + activationState);
                            }
                            r.callback.onDataActivationStateChanged(activationState);
                        }
                    }  catch (RemoteException ex) {
                        mRemoveList.add(r.binder);
                    }
                }
            } else {
                log("notifySimActivationStateForPhoneId: INVALID phoneId=" + phoneId);
            }
            handleRemoveListLocked();
        }
    }

    public void notifySignalStrengthForPhoneId(int phoneId, int subId,
                SignalStrength signalStrength) {
        if (!checkNotifyPermission("notifySignalStrength()")) {
            return;
        }
        if (VDBG) {
            log("notifySignalStrengthForPhoneId: subId=" + subId
                +" phoneId=" + phoneId + " signalStrength=" + signalStrength);
        }

        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                if (VDBG) log("notifySignalStrengthForPhoneId: valid phoneId=" + phoneId);
                mSignalStrength[phoneId] = signalStrength;
                for (Record r : mRecords) {
                    if (VDBG) {
                        log("notifySignalStrengthForPhoneId: r=" + r + " subId=" + subId
                                + " phoneId=" + phoneId + " ss=" + signalStrength);
                    }
                    if ((r.matchTelephonyCallbackEvent(
                            TelephonyCallback.EVENT_SIGNAL_STRENGTHS_CHANGED)
                            || r.matchTelephonyCallbackEvent(
                            TelephonyCallback.EVENT_ALWAYS_REPORTED_SIGNAL_STRENGTH_CHANGED))
                            && idMatch(r.subId, subId, phoneId)) {
                        try {
                            if (DBG) {
                                log("notifySignalStrengthForPhoneId: callback.onSsS r=" + r
                                        + " subId=" + subId + " phoneId=" + phoneId
                                        + " ss=" + signalStrength);
                            }
                            r.callback.onSignalStrengthsChanged(new SignalStrength(signalStrength));
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                    if (r.matchTelephonyCallbackEvent(
                            TelephonyCallback.EVENT_SIGNAL_STRENGTH_CHANGED)
                            && idMatch(r.subId, subId, phoneId)) {
                        try {
                            int gsmSignalStrength = signalStrength.getGsmSignalStrength();
                            int ss = (gsmSignalStrength == 99 ? -1 : gsmSignalStrength);
                            if (DBG) {
                                log("notifySignalStrengthForPhoneId: callback.onSS r=" + r
                                        + " subId=" + subId + " phoneId=" + phoneId
                                        + " gsmSS=" + gsmSignalStrength + " ss=" + ss);
                            }
                            r.callback.onSignalStrengthChanged(ss);
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }
            } else {
                log("notifySignalStrengthForPhoneId: invalid phoneId=" + phoneId);
            }
            handleRemoveListLocked();
        }
        broadcastSignalStrengthChanged(signalStrength, phoneId, subId);
    }

    @Override
    public void notifyCarrierNetworkChange(boolean active) {
        // only CarrierService with carrier privilege rule should have the permission
        int[] subIds = Arrays.stream(SubscriptionManager.from(mContext)
                    .getCompleteActiveSubscriptionIdList())
                    .filter(i -> TelephonyPermissions.checkCarrierPrivilegeForSubId(mContext,
                            i)).toArray();
        if (ArrayUtils.isEmpty(subIds)) {
            loge("notifyCarrierNetworkChange without carrier privilege");
            // the active subId does not have carrier privilege.
            throw new SecurityException("notifyCarrierNetworkChange without carrier privilege");
        }

        synchronized (mRecords) {
            mCarrierNetworkChangeState = active;
            for (int subId : subIds) {
                int phoneId = getPhoneIdFromSubId(subId);

                if (VDBG) {
                    log("notifyCarrierNetworkChange: active=" + active + "subId: " + subId);
                }
                for (Record r : mRecords) {
                    if (r.matchTelephonyCallbackEvent(
                            TelephonyCallback.EVENT_CARRIER_NETWORK_CHANGED)
                            && idMatch(r.subId, subId, phoneId)) {
                        try {
                            r.callback.onCarrierNetworkChange(active);
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    public void notifyCellInfo(List<CellInfo> cellInfo) {
         notifyCellInfoForSubscriber(SubscriptionManager.DEFAULT_SUBSCRIPTION_ID, cellInfo);
    }

    public void notifyCellInfoForSubscriber(int subId, List<CellInfo> cellInfo) {
        if (!checkNotifyPermission("notifyCellInfoForSubscriber()")) {
            return;
        }
        if (VDBG) {
            log("notifyCellInfoForSubscriber: subId=" + subId
                + " cellInfo=" + cellInfo);
        }
        int phoneId = getPhoneIdFromSubId(subId);
        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                mCellInfo.set(phoneId, cellInfo);
                for (Record r : mRecords) {
                    if (validateEventAndUserLocked(
                            r, TelephonyCallback.EVENT_CELL_INFO_CHANGED)
                            && idMatch(r.subId, subId, phoneId)
                            && (checkCoarseLocationAccess(r, Build.VERSION_CODES.BASE)
                                    && checkFineLocationAccess(r, Build.VERSION_CODES.Q))) {
                        try {
                            if (DBG_LOC) {
                                log("notifyCellInfoForSubscriber: mCellInfo=" + cellInfo
                                    + " r=" + r);
                            }
                            r.callback.onCellInfoChanged(cellInfo);
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    @Override
    public void notifyMessageWaitingChangedForPhoneId(int phoneId, int subId, boolean mwi) {
        if (!checkNotifyPermission("notifyMessageWaitingChanged()")) {
            return;
        }
        if (VDBG) {
            log("notifyMessageWaitingChangedForSubscriberPhoneID: subId=" + phoneId
                + " mwi=" + mwi);
        }
        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                mMessageWaiting[phoneId] = mwi;
                for (Record r : mRecords) {
                    if (r.matchTelephonyCallbackEvent(
                            TelephonyCallback.EVENT_MESSAGE_WAITING_INDICATOR_CHANGED)
                            && idMatch(r.subId, subId, phoneId)) {
                        try {
                            r.callback.onMessageWaitingIndicatorChanged(mwi);
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    public void notifyUserMobileDataStateChangedForPhoneId(int phoneId, int subId, boolean state) {
        if (!checkNotifyPermission("notifyUserMobileDataStateChanged()")) {
            return;
        }
        if (VDBG) {
            log("notifyUserMobileDataStateChangedForSubscriberPhoneID: PhoneId=" + phoneId
                    + " subId=" + subId + " state=" + state);
        }
        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                mUserMobileDataState[phoneId] = state;
                for (Record r : mRecords) {
                    if (r.matchTelephonyCallbackEvent(
                            TelephonyCallback.EVENT_USER_MOBILE_DATA_STATE_CHANGED)
                            && idMatch(r.subId, subId, phoneId)) {
                        try {
                            r.callback.onUserMobileDataStateChanged(state);
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    /**
     * Notify display network info changed.
     *
     * @param phoneId Phone id
     * @param subId Subscription id
     * @param telephonyDisplayInfo Display network info
     *
     * @see PhoneStateListener#onDisplayInfoChanged(TelephonyDisplayInfo)
     */
    public void notifyDisplayInfoChanged(int phoneId, int subId,
                                         @NonNull TelephonyDisplayInfo telephonyDisplayInfo) {
        if (!checkNotifyPermission("notifyDisplayInfoChanged()")) {
            return;
        }
        String str = "notifyDisplayInfoChanged: PhoneId=" + phoneId + " subId=" + subId
                + " telephonyDisplayInfo=" + telephonyDisplayInfo;
        if (VDBG) {
            log(str);
        }
        mLocalLog.log(str);
        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                mTelephonyDisplayInfos[phoneId] = telephonyDisplayInfo;
                for (Record r : mRecords) {
                    if (r.matchTelephonyCallbackEvent(
                            TelephonyCallback.EVENT_DISPLAY_INFO_CHANGED)
                            && idMatchWithoutDefaultPhoneCheck(r.subId, subId)) {
                        try {
                            if (!mConfigurationProvider.isDisplayInfoNrAdvancedSupported(
                                    r.callingPackage, Binder.getCallingUserHandle())) {
                                r.callback.onDisplayInfoChanged(
                                        getBackwardCompatibleTelephonyDisplayInfo(
                                                telephonyDisplayInfo));
                            } else {
                                r.callback.onDisplayInfoChanged(telephonyDisplayInfo);
                            }
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    private TelephonyDisplayInfo getBackwardCompatibleTelephonyDisplayInfo(
            @NonNull TelephonyDisplayInfo telephonyDisplayInfo) {
        int networkType = telephonyDisplayInfo.getNetworkType();
        int overrideNetworkType = telephonyDisplayInfo.getOverrideNetworkType();
        if (networkType == TelephonyManager.NETWORK_TYPE_NR) {
            overrideNetworkType = TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE;
        } else if (networkType == TelephonyManager.NETWORK_TYPE_LTE
                && overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED) {
            overrideNetworkType = TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA_MMWAVE;
        }
        return new TelephonyDisplayInfo(networkType, overrideNetworkType);
    }

    public void notifyCallForwardingChanged(boolean cfi) {
        notifyCallForwardingChangedForSubscriber(SubscriptionManager.DEFAULT_SUBSCRIPTION_ID, cfi);
    }

    public void notifyCallForwardingChangedForSubscriber(int subId, boolean cfi) {
        if (!checkNotifyPermission("notifyCallForwardingChanged()")) {
            return;
        }
        if (VDBG) {
            log("notifyCallForwardingChangedForSubscriber: subId=" + subId
                + " cfi=" + cfi);
        }
        int phoneId = getPhoneIdFromSubId(subId);
        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                mCallForwarding[phoneId] = cfi;
                for (Record r : mRecords) {
                    if (r.matchTelephonyCallbackEvent(
                            TelephonyCallback.EVENT_CALL_FORWARDING_INDICATOR_CHANGED)
                            && idMatch(r.subId, subId, phoneId)) {
                        try {
                            r.callback.onCallForwardingIndicatorChanged(cfi);
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    public void notifyDataActivity(int state) {
        notifyDataActivityForSubscriber(SubscriptionManager.DEFAULT_SUBSCRIPTION_ID, state);
    }

    public void notifyDataActivityForSubscriber(int subId, int state) {
        if (!checkNotifyPermission("notifyDataActivity()" )) {
            return;
        }
        int phoneId = getPhoneIdFromSubId(subId);
        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                mDataActivity[phoneId] = state;
                for (Record r : mRecords) {
                    // Notify by correct subId.
                    if (r.matchTelephonyCallbackEvent(
                            TelephonyCallback.EVENT_DATA_ACTIVITY_CHANGED)
                            && idMatch(r.subId, subId, phoneId)) {
                        try {
                            r.callback.onDataActivity(state);
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    /**
     * Send a notification to registrants that the data connection state has changed.
     *
     * @param phoneId the phoneId carrying the data connection
     * @param subId the subscriptionId for the data connection
     * @param preciseState a PreciseDataConnectionState that has info about the data connection
     */
    @Override
    public void notifyDataConnectionForSubscriber(int phoneId, int subId,
            @NonNull PreciseDataConnectionState preciseState) {
        if (!checkNotifyPermission("notifyDataConnection()" )) {
            return;
        }

        ApnSetting apnSetting = preciseState.getApnSetting();

        int apnTypes = apnSetting.getApnTypeBitmask();
        int state = preciseState.getState();
        int networkType = preciseState.getNetworkType();

        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                // We only call the callback when the change is for default APN type.
                if ((ApnSetting.TYPE_DEFAULT & apnTypes) != 0
                        && (mDataConnectionState[phoneId] != state
                        || mDataConnectionNetworkType[phoneId] != networkType)) {
                    String str = "onDataConnectionStateChanged("
                            + TelephonyUtils.dataStateToString(state)
                            + ", " + getNetworkTypeName(networkType)
                            + ") subId=" + subId + ", phoneId=" + phoneId;
                    log(str);
                    mLocalLog.log(str);
                    for (Record r : mRecords) {
                        if (r.matchTelephonyCallbackEvent(
                                TelephonyCallback.EVENT_DATA_CONNECTION_STATE_CHANGED)
                                && idMatch(r.subId, subId, phoneId)) {
                            try {
                                if (DBG) {
                                    log("Notify data connection state changed on sub: " + subId);
                                }
                                r.callback.onDataConnectionStateChanged(state, networkType);
                            } catch (RemoteException ex) {
                                mRemoveList.add(r.binder);
                            }
                        }
                    }
                    handleRemoveListLocked();

                    mDataConnectionState[phoneId] = state;
                    mDataConnectionNetworkType[phoneId] = networkType;
                }

                Pair<Integer, ApnSetting> key = Pair.create(preciseState.getTransportType(),
                        preciseState.getApnSetting());
                PreciseDataConnectionState oldState = mPreciseDataConnectionStates.get(phoneId)
                        .remove(key);
                if (!Objects.equals(oldState, preciseState)) {
                    for (Record r : mRecords) {
                        if (r.matchTelephonyCallbackEvent(
                                TelephonyCallback.EVENT_PRECISE_DATA_CONNECTION_STATE_CHANGED)
                                && idMatch(r.subId, subId, phoneId)) {
                            try {
                                r.callback.onPreciseDataConnectionStateChanged(preciseState);
                            } catch (RemoteException ex) {
                                mRemoveList.add(r.binder);
                            }
                        }
                    }
                    handleRemoveListLocked();

                    broadcastDataConnectionStateChanged(phoneId, subId, preciseState);

                    String str = "notifyDataConnectionForSubscriber: phoneId=" + phoneId + " subId="
                            + subId + " " + preciseState;
                    log(str);
                    mLocalLog.log(str);
                }

                // If the state is disconnected, it would be the end of life cycle of a data
                // connection, so remove it from the cache.
                if (preciseState.getState() != TelephonyManager.DATA_DISCONNECTED) {
                    mPreciseDataConnectionStates.get(phoneId).put(key, preciseState);
                }
            }
        }
    }

    @Override
    public void notifyCellLocationForSubscriber(int subId, CellIdentity cellIdentity) {
        notifyCellLocationForSubscriber(subId, cellIdentity, false /* hasUserSwitched */);
    }

    private void notifyCellLocationForSubscriber(int subId, CellIdentity cellIdentity,
            boolean hasUserSwitched) {
        log("notifyCellLocationForSubscriber: subId=" + subId + " cellIdentity="
                + Rlog.pii(DBG || VDBG || DBG_LOC, cellIdentity));
        if (!checkNotifyPermission("notifyCellLocation()")) {
            return;
        }
        int phoneId = getPhoneIdFromSubId(subId);
        synchronized (mRecords) {
            if (validatePhoneId(phoneId)
                    && (hasUserSwitched || !Objects.equals(cellIdentity, mCellIdentity[phoneId]))) {
                mCellIdentity[phoneId] = cellIdentity;
                for (Record r : mRecords) {
                    if (validateEventAndUserLocked(
                            r, TelephonyCallback.EVENT_CELL_LOCATION_CHANGED)
                            && idMatch(r.subId, subId, phoneId)
                            && (checkCoarseLocationAccess(r, Build.VERSION_CODES.BASE)
                                    && checkFineLocationAccess(r, Build.VERSION_CODES.Q))) {
                        try {
                            if (DBG_LOC) {
                                log("notifyCellLocation: cellIdentity=" + cellIdentity
                                        + " r=" + r);
                            }
                            r.callback.onCellLocationChanged(cellIdentity);
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    public void notifyPreciseCallState(int phoneId, int subId, int ringingCallState,
                                       int foregroundCallState, int backgroundCallState) {
        if (!checkNotifyPermission("notifyPreciseCallState()")) {
            return;
        }
        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                mRingingCallState[phoneId] = ringingCallState;
                mForegroundCallState[phoneId] = foregroundCallState;
                mBackgroundCallState[phoneId] = backgroundCallState;
                mPreciseCallState[phoneId] = new PreciseCallState(
                        ringingCallState, foregroundCallState,
                        backgroundCallState,
                        DisconnectCause.NOT_VALID,
                        PreciseDisconnectCause.NOT_VALID);
                boolean notifyCallAttributes = true;
                if (mCallQuality == null) {
                    log("notifyPreciseCallState: mCallQuality is null, "
                            + "skipping call attributes");
                    notifyCallAttributes = false;
                } else {
                    // If the precise call state is no longer active, reset the call network type
                    // and call quality.
                    if (mPreciseCallState[phoneId].getForegroundCallState()
                            != PreciseCallState.PRECISE_CALL_STATE_ACTIVE) {
                        mCallNetworkType[phoneId] = TelephonyManager.NETWORK_TYPE_UNKNOWN;
                        mCallQuality[phoneId] = createCallQuality();
                    }
                    mCallAttributes[phoneId] = new CallAttributes(mPreciseCallState[phoneId],
                            mCallNetworkType[phoneId], mCallQuality[phoneId]);
                }

                for (Record r : mRecords) {
                    if (r.matchTelephonyCallbackEvent(
                            TelephonyCallback.EVENT_PRECISE_CALL_STATE_CHANGED)
                            && idMatch(r.subId, subId, phoneId)) {
                        try {
                            r.callback.onPreciseCallStateChanged(mPreciseCallState[phoneId]);
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                    if (notifyCallAttributes && r.matchTelephonyCallbackEvent(
                            TelephonyCallback.EVENT_CALL_ATTRIBUTES_CHANGED)
                            && idMatch(r.subId, subId, phoneId)) {
                        try {
                            r.callback.onCallAttributesChanged(mCallAttributes[phoneId]);
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    public void notifyDisconnectCause(int phoneId, int subId, int disconnectCause,
                                      int preciseDisconnectCause) {
        if (!checkNotifyPermission("notifyDisconnectCause()")) {
            return;
        }
        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                mCallDisconnectCause[phoneId] = disconnectCause;
                mCallPreciseDisconnectCause[phoneId] = preciseDisconnectCause;
                for (Record r : mRecords) {
                    if (r.matchTelephonyCallbackEvent(
                            TelephonyCallback.EVENT_CALL_DISCONNECT_CAUSE_CHANGED)
                            && idMatch(r.subId, subId, phoneId)) {
                        try {
                            r.callback.onCallDisconnectCauseChanged(mCallDisconnectCause[phoneId],
                                    mCallPreciseDisconnectCause[phoneId]);
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    public void notifyImsDisconnectCause(int subId, ImsReasonInfo imsReasonInfo) {
        if (!checkNotifyPermission("notifyImsCallDisconnectCause()")) {
            return;
        }
        int phoneId = getPhoneIdFromSubId(subId);
        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                mImsReasonInfo.set(phoneId, imsReasonInfo);
                for (Record r : mRecords) {
                    if (r.matchTelephonyCallbackEvent(
                            TelephonyCallback.EVENT_IMS_CALL_DISCONNECT_CAUSE_CHANGED)
                            && idMatch(r.subId, subId, phoneId)) {
                        try {
                            if (DBG_LOC) {
                                log("notifyImsCallDisconnectCause: mImsReasonInfo="
                                        + imsReasonInfo + " r=" + r);
                            }
                            r.callback.onImsCallDisconnectCauseChanged(mImsReasonInfo.get(phoneId));
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    @Override
    public void notifySrvccStateChanged(int subId, @SrvccState int state) {
        if (!checkNotifyPermission("notifySrvccStateChanged()")) {
            return;
        }
        if (VDBG) {
            log("notifySrvccStateChanged: subId=" + subId + " srvccState=" + state);
        }
        int phoneId = getPhoneIdFromSubId(subId);
        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                mSrvccState[phoneId] = state;
                for (Record r : mRecords) {
                    if (r.matchTelephonyCallbackEvent(
                            TelephonyCallback.EVENT_SRVCC_STATE_CHANGED)
                            && idMatch(r.subId, subId, phoneId)) {
                        try {
                            if (DBG_LOC) {
                                log("notifySrvccStateChanged: mSrvccState=" + state + " r=" + r);
                            }
                            r.callback.onSrvccStateChanged(state);
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    public void notifyOemHookRawEventForSubscriber(int phoneId, int subId, byte[] rawData) {
        if (!checkNotifyPermission("notifyOemHookRawEventForSubscriber")) {
            return;
        }

        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                for (Record r : mRecords) {
                    if (VDBG) {
                        log("notifyOemHookRawEventForSubscriber:  r=" + r + " subId=" + subId);
                    }
                    if ((r.matchTelephonyCallbackEvent(
                            TelephonyCallback.EVENT_OEM_HOOK_RAW))
                            && idMatch(r.subId, subId, phoneId)) {
                        try {
                            r.callback.onOemHookRawEvent(rawData);
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    public void notifyPhoneCapabilityChanged(PhoneCapability capability) {
        if (!checkNotifyPermission("notifyPhoneCapabilityChanged()")) {
            return;
        }

        if (VDBG) {
            log("notifyPhoneCapabilityChanged: capability=" + capability);
        }

        synchronized (mRecords) {
            mPhoneCapability = capability;

            for (Record r : mRecords) {
                if (r.matchTelephonyCallbackEvent(
                        TelephonyCallback.EVENT_PHONE_CAPABILITY_CHANGED)) {
                    try {
                        r.callback.onPhoneCapabilityChanged(capability);
                    } catch (RemoteException ex) {
                        mRemoveList.add(r.binder);
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    public void notifyActiveDataSubIdChanged(int activeDataSubId) {
        if (!checkNotifyPermission("notifyActiveDataSubIdChanged()")) {
            return;
        }

        if (VDBG) {
            log("notifyActiveDataSubIdChanged: activeDataSubId=" + activeDataSubId);
        }

        mActiveDataSubId = activeDataSubId;
        synchronized (mRecords) {
            for (Record r : mRecords) {
                if (r.matchTelephonyCallbackEvent(
                        TelephonyCallback.EVENT_ACTIVE_DATA_SUBSCRIPTION_ID_CHANGED)) {
                    try {
                        r.callback.onActiveDataSubIdChanged(activeDataSubId);
                    } catch (RemoteException ex) {
                        mRemoveList.add(r.binder);
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    public void notifyRadioPowerStateChanged(int phoneId, int subId, @RadioPowerState int state) {
        if (!checkNotifyPermission("notifyRadioPowerStateChanged()")) {
            return;
        }

        if (VDBG) {
            log("notifyRadioPowerStateChanged: state= " + state + " subId=" + subId);
        }

        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                mRadioPowerState = state;

                for (Record r : mRecords) {
                    if (r.matchTelephonyCallbackEvent(
                            TelephonyCallback.EVENT_RADIO_POWER_STATE_CHANGED)
                            && idMatch(r.subId, subId, phoneId)) {
                        try {
                            r.callback.onRadioPowerStateChanged(state);
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }

            }
            handleRemoveListLocked();
        }
        broadcastRadioPowerStateChanged(state, phoneId, subId);
    }

    @Override
    public void notifyEmergencyNumberList(int phoneId, int subId) {
        if (!checkNotifyPermission("notifyEmergencyNumberList()")) {
            return;
        }

        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                TelephonyManager tm = (TelephonyManager) mContext.getSystemService(
                        Context.TELEPHONY_SERVICE);
                mEmergencyNumberList = tm.getEmergencyNumberList();

                for (Record r : mRecords) {
                    if (r.matchTelephonyCallbackEvent(
                            TelephonyCallback.EVENT_EMERGENCY_NUMBER_LIST_CHANGED)
                            && idMatch(r.subId, subId, phoneId)) {
                        try {
                            r.callback.onEmergencyNumberListChanged(mEmergencyNumberList);
                            if (VDBG) {
                                log("notifyEmergencyNumberList: emergencyNumberList= "
                                        + mEmergencyNumberList);
                            }
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }
            }

            handleRemoveListLocked();
        }
    }

    @Override
    public void notifyOutgoingEmergencyCall(int phoneId, int subId,
            EmergencyNumber emergencyNumber) {
        if (!checkNotifyPermission("notifyOutgoingEmergencyCall()")) {
            return;
        }
        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                mOutgoingCallEmergencyNumber[phoneId] = emergencyNumber;
            }
            for (Record r : mRecords) {
                // Send to all listeners regardless of subscription
                if (r.matchTelephonyCallbackEvent(
                        TelephonyCallback.EVENT_OUTGOING_EMERGENCY_CALL)) {
                    try {
                        r.callback.onOutgoingEmergencyCall(emergencyNumber, subId);
                    } catch (RemoteException ex) {
                        mRemoveList.add(r.binder);
                    }
                }
            }
        }
        handleRemoveListLocked();
    }

    @Override
    public void notifyOutgoingEmergencySms(int phoneId, int subId,
            EmergencyNumber emergencyNumber) {
        if (!checkNotifyPermission("notifyOutgoingEmergencySms()")) {
            return;
        }

        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                mOutgoingSmsEmergencyNumber[phoneId] = emergencyNumber;
                for (Record r : mRecords) {
                    // Send to all listeners regardless of subscription
                    if (r.matchTelephonyCallbackEvent(
                            TelephonyCallback.EVENT_OUTGOING_EMERGENCY_SMS)) {
                        try {
                            r.callback.onOutgoingEmergencySms(emergencyNumber, subId);
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    @Override
    public void notifyCallQualityChanged(CallQuality callQuality, int phoneId, int subId,
            int callNetworkType) {
        if (!checkNotifyPermission("notifyCallQualityChanged()")) {
            return;
        }

        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                // merge CallQuality with PreciseCallState and network type
                mCallQuality[phoneId] = callQuality;
                mCallNetworkType[phoneId] = callNetworkType;
                mCallAttributes[phoneId] = new CallAttributes(mPreciseCallState[phoneId],
                        callNetworkType, callQuality);

                for (Record r : mRecords) {
                    if (r.matchTelephonyCallbackEvent(
                            TelephonyCallback.EVENT_CALL_ATTRIBUTES_CHANGED)
                            && idMatch(r.subId, subId, phoneId)) {
                        try {
                            r.callback.onCallAttributesChanged(mCallAttributes[phoneId]);
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }
            }

            handleRemoveListLocked();
        }
    }

    @Override
    public void notifyRegistrationFailed(int phoneId, int subId, @NonNull CellIdentity cellIdentity,
            @NonNull String chosenPlmn, int domain, int causeCode, int additionalCauseCode) {
        if (!checkNotifyPermission("notifyRegistrationFailed()")) {
            return;
        }

        // In case callers don't have fine location access, pre-construct a location-free version
        // of the CellIdentity. This will still have the PLMN ID, which should be sufficient for
        // most purposes.
        final CellIdentity noLocationCi = cellIdentity.sanitizeLocationInfo();

        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                for (Record r : mRecords) {
                    if (r.matchTelephonyCallbackEvent(
                            TelephonyCallback.EVENT_REGISTRATION_FAILURE)
                            && idMatch(r.subId, subId, phoneId)) {
                        try {
                            r.callback.onRegistrationFailed(
                                    checkFineLocationAccess(r, Build.VERSION_CODES.BASE)
                                            ? cellIdentity : noLocationCi,
                                    chosenPlmn, domain, causeCode,
                                    additionalCauseCode);
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    /**
     * Send a notification of changes to barring status to PhoneStateListener registrants.
     *
     * @param phoneId the phoneId
     * @param subId the subId
     * @param barringInfo a structure containing the complete updated barring info.
     */
    public void notifyBarringInfoChanged(int phoneId, int subId, @NonNull BarringInfo barringInfo) {
        if (!checkNotifyPermission("notifyBarringInfo()")) {
            return;
        }
        if (barringInfo == null) {
            log("Received null BarringInfo for subId=" + subId + ", phoneId=" + phoneId);
            mBarringInfo.set(phoneId, new BarringInfo());
            return;
        }

        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                mBarringInfo.set(phoneId, barringInfo);
                // Barring info is non-null
                BarringInfo biNoLocation = barringInfo.createLocationInfoSanitizedCopy();
                if (VDBG) log("listen: call onBarringInfoChanged=" + barringInfo);
                for (Record r : mRecords) {
                    if (r.matchTelephonyCallbackEvent(
                            TelephonyCallback.EVENT_BARRING_INFO_CHANGED)
                            && idMatch(r.subId, subId, phoneId)) {
                        try {
                            if (DBG_LOC) {
                                log("notifyBarringInfo: mBarringInfo="
                                        + barringInfo + " r=" + r);
                            }
                            r.callback.onBarringInfoChanged(
                                    checkFineLocationAccess(r, Build.VERSION_CODES.BASE)
                                        ? barringInfo : biNoLocation);
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    /**
     * Send a notification to registrants that the configs of physical channel has changed for
     * a particular subscription.
     *
     * @param phoneId the phone id.
     * @param subId the subId
     * @param configs a list of {@link PhysicalChannelConfig}, the configs of physical channel.
     */
    public void notifyPhysicalChannelConfigForSubscriber(int phoneId, int subId,
            List<PhysicalChannelConfig> configs) {
        if (!checkNotifyPermission("notifyPhysicalChannelConfig()")) {
            return;
        }

        List<PhysicalChannelConfig> sanitizedConfigs = getLocationSanitizedConfigs(configs);
        if (VDBG) {
            log("notifyPhysicalChannelConfig: subId=" + subId + " configs=" + configs
                    + " sanitizedConfigs=" + sanitizedConfigs);
        }

        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                mPhysicalChannelConfigs.set(phoneId, configs);
                for (Record r : mRecords) {
                    if (r.matchTelephonyCallbackEvent(
                            TelephonyCallback.EVENT_PHYSICAL_CHANNEL_CONFIG_CHANGED)
                            && idMatch(r.subId, subId, phoneId)) {
                        try {
                            if (DBG_LOC) {
                                log("notifyPhysicalChannelConfig: mPhysicalChannelConfigs="
                                        + (shouldSanitizeLocationForPhysicalChannelConfig(r)
                                                ? sanitizedConfigs : configs)
                                        + " r=" + r);
                            }
                            r.callback.onPhysicalChannelConfigChanged(
                                    shouldSanitizeLocationForPhysicalChannelConfig(r)
                                            ? sanitizedConfigs : configs);
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    private static boolean shouldSanitizeLocationForPhysicalChannelConfig(Record record) {
        // Always redact location info from PhysicalChannelConfig if the registrant is from neither
        // PHONE nor SYSTEM process. There is no user case that the registrant needs the location
        // info (e.g. physicalCellId). This also remove the need for the location permissions check.
        return record.callerUid != Process.PHONE_UID && record.callerUid != Process.SYSTEM_UID;
    }

    /**
     * Return a copy of the PhysicalChannelConfig list but with location info removed.
     */
    private static List<PhysicalChannelConfig> getLocationSanitizedConfigs(
            List<PhysicalChannelConfig> configs) {
        List<PhysicalChannelConfig> sanitizedConfigs = new ArrayList<>(configs.size());
        for (PhysicalChannelConfig config : configs) {
            sanitizedConfigs.add(config.createLocationInfoSanitizedCopy());
        }
        return sanitizedConfigs;
    }

    /**
     * Notify that the data enabled has changed.
     *
     * @param phoneId the phone id.
     * @param subId the subId.
     * @param enabled True if data is enabled, otherwise disabled.
     * @param reason  Reason for data enabled/disabled. See {@code DATA_*} in
     *                {@link TelephonyManager}.
     */
    public void notifyDataEnabled(int phoneId, int subId, boolean enabled,
            @TelephonyManager.DataEnabledReason int reason) {
        if (!checkNotifyPermission("notifyDataEnabled()")) {
            return;
        }

        if (VDBG) {
            log("notifyDataEnabled: PhoneId=" + phoneId + " subId=" + subId +
                    " enabled=" + enabled + " reason=" + reason);
        }

        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                mIsDataEnabled[phoneId] = enabled;
                mDataEnabledReason[phoneId] = reason;
                for (Record r : mRecords) {
                    if (r.matchTelephonyCallbackEvent(
                            TelephonyCallback.EVENT_DATA_ENABLED_CHANGED)
                            && idMatch(r.subId, subId, phoneId)) {
                        try {
                            r.callback.onDataEnabledChanged(enabled, reason);
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    /**
     * Notify that the allowed network type has changed.
     *
     * @param phoneId the phone id.
     * @param subId the subId.
     * @param reason the allowed network type reason.
     * @param allowedNetworkType the allowed network type value.
     */
    public void notifyAllowedNetworkTypesChanged(int phoneId, int subId, int reason,
            long allowedNetworkType) {
        if (!checkNotifyPermission("notifyAllowedNetworkTypesChanged()")) {
            return;
        }

        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                mAllowedNetworkTypeReason[phoneId] = reason;
                mAllowedNetworkTypeValue[phoneId] = allowedNetworkType;

                for (Record r : mRecords) {
                    if (r.matchTelephonyCallbackEvent(
                            TelephonyCallback.EVENT_ALLOWED_NETWORK_TYPE_LIST_CHANGED)
                            && idMatch(r.subId, subId, phoneId)) {
                        try {
                            if (VDBG) {
                                log("notifyAllowedNetworkTypesChanged: reason= " + reason
                                        + ", allowed network type:"
                                        + TelephonyManager.convertNetworkTypeBitmaskToString(
                                        allowedNetworkType));
                            }
                            r.callback.onAllowedNetworkTypesChanged(reason, allowedNetworkType);
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    /**
     * Notify that the link capacity estimate has changed.
     * @param phoneId the phone id.
     * @param subId the subscription id.
     * @param linkCapacityEstimateList a list of {@link LinkCapacityEstimate}
     */
    public void notifyLinkCapacityEstimateChanged(int phoneId, int subId,
            List<LinkCapacityEstimate> linkCapacityEstimateList) {
        if (!checkNotifyPermission("notifyLinkCapacityEstimateChanged()")) {
            return;
        }

        if (VDBG) {
            log("notifyLinkCapacityEstimateChanged: linkCapacityEstimateList ="
                    + linkCapacityEstimateList);
        }

        synchronized (mRecords) {
            if (validatePhoneId(phoneId)) {
                mLinkCapacityEstimateLists.set(phoneId, linkCapacityEstimateList);
                for (Record r : mRecords) {
                    if (r.matchTelephonyCallbackEvent(
                            TelephonyCallback.EVENT_LINK_CAPACITY_ESTIMATE_CHANGED)
                            && idMatch(r.subId, subId, phoneId)) {
                        try {
                            r.callback.onLinkCapacityEstimateChanged(linkCapacityEstimateList);
                        } catch (RemoteException ex) {
                            mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        final IndentingPrintWriter pw = new IndentingPrintWriter(writer, "  ");

        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;

        synchronized (mRecords) {
            final int recordCount = mRecords.size();
            pw.println("last known state:");
            pw.increaseIndent();
            for (int i = 0; i < getTelephonyManager().getActiveModemCount(); i++) {
                pw.println("Phone Id=" + i);
                pw.increaseIndent();
                pw.println("mCallState=" + mCallState[i]);
                pw.println("mRingingCallState=" + mRingingCallState[i]);
                pw.println("mForegroundCallState=" + mForegroundCallState[i]);
                pw.println("mBackgroundCallState=" + mBackgroundCallState[i]);
                pw.println("mPreciseCallState=" + mPreciseCallState[i]);
                pw.println("mCallDisconnectCause=" + mCallDisconnectCause[i]);
                pw.println("mCallIncomingNumber=" + mCallIncomingNumber[i]);
                pw.println("mServiceState=" + mServiceState[i]);
                pw.println("mVoiceActivationState= " + mVoiceActivationState[i]);
                pw.println("mDataActivationState= " + mDataActivationState[i]);
                pw.println("mUserMobileDataState= " + mUserMobileDataState[i]);
                pw.println("mSignalStrength=" + mSignalStrength[i]);
                pw.println("mMessageWaiting=" + mMessageWaiting[i]);
                pw.println("mCallForwarding=" + mCallForwarding[i]);
                pw.println("mDataActivity=" + mDataActivity[i]);
                pw.println("mDataConnectionState=" + mDataConnectionState[i]);
                pw.println("mCellIdentity=" + mCellIdentity[i]);
                pw.println("mCellInfo=" + mCellInfo.get(i));
                pw.println("mImsCallDisconnectCause=" + mImsReasonInfo.get(i));
                pw.println("mSrvccState=" + mSrvccState[i]);
                pw.println("mCallPreciseDisconnectCause=" + mCallPreciseDisconnectCause[i]);
                pw.println("mCallQuality=" + mCallQuality[i]);
                pw.println("mCallAttributes=" + mCallAttributes[i]);
                pw.println("mCallNetworkType=" + mCallNetworkType[i]);
                pw.println("mPreciseDataConnectionStates=" + mPreciseDataConnectionStates.get(i));
                pw.println("mOutgoingCallEmergencyNumber=" + mOutgoingCallEmergencyNumber[i]);
                pw.println("mOutgoingSmsEmergencyNumber=" + mOutgoingSmsEmergencyNumber[i]);
                pw.println("mBarringInfo=" + mBarringInfo.get(i));
                pw.println("mTelephonyDisplayInfo=" + mTelephonyDisplayInfos[i]);
                pw.println("mIsDataEnabled=" + mIsDataEnabled);
                pw.println("mDataEnabledReason=" + mDataEnabledReason);
                pw.println("mAllowedNetworkTypeReason=" + mAllowedNetworkTypeReason[i]);
                pw.println("mAllowedNetworkTypeValue=" + mAllowedNetworkTypeValue[i]);
                pw.println("mPhysicalChannelConfigs=" + mPhysicalChannelConfigs.get(i));
                pw.println("mLinkCapacityEstimateList=" + mLinkCapacityEstimateLists.get(i));
                pw.decreaseIndent();
            }
            pw.println("mCarrierNetworkChangeState=" + mCarrierNetworkChangeState);
            pw.println("mPhoneCapability=" + mPhoneCapability);
            pw.println("mActiveDataSubId=" + mActiveDataSubId);
            pw.println("mRadioPowerState=" + mRadioPowerState);
            pw.println("mEmergencyNumberList=" + mEmergencyNumberList);
            pw.println("mDefaultPhoneId=" + mDefaultPhoneId);
            pw.println("mDefaultSubId=" + mDefaultSubId);

            pw.decreaseIndent();

            pw.println("local logs:");
            pw.increaseIndent();
            mLocalLog.dump(fd, pw, args);
            pw.decreaseIndent();
            pw.println("listen logs:");
            pw.increaseIndent();
            mListenLog.dump(fd, pw, args);
            pw.decreaseIndent();
            pw.println("registrations: count=" + recordCount);
            pw.increaseIndent();
            for (Record r : mRecords) {
                pw.println(r);
            }
            pw.decreaseIndent();
        }
    }

    //
    // the legacy intent broadcasting
    //

    // Legacy intent action.
    /** Fired when a subscription's phone state changes. */
    private static final String ACTION_SUBSCRIPTION_PHONE_STATE_CHANGED =
            "android.intent.action.SUBSCRIPTION_PHONE_STATE";
    /**
     * Broadcast Action: The data connection state has changed for any one of the
     * phone's mobile data connections (eg, default, MMS or GPS specific connection).
     */
    private static final String ACTION_ANY_DATA_CONNECTION_STATE_CHANGED =
            "android.intent.action.ANY_DATA_STATE";

    // Legacy intent extra keys, copied from PhoneConstants.
    // Used in legacy intents sent here, for backward compatibility.
    private static final String PHONE_CONSTANTS_DATA_APN_TYPE_KEY = "apnType";
    private static final String PHONE_CONSTANTS_DATA_APN_KEY = "apn";
    private static final String PHONE_CONSTANTS_SLOT_KEY = "slot";
    private static final String PHONE_CONSTANTS_STATE_KEY = "state";
    private static final String PHONE_CONSTANTS_SUBSCRIPTION_KEY = "subscription";

    /**
     * Broadcast Action: The phone's signal strength has changed. The intent will have the
     * following extra values:
     *   phoneName - A string version of the phone name.
     *   asu - A numeric value for the signal strength.
     *         An ASU is 0-31 or -1 if unknown (for GSM, dBm = -113 - 2 * asu).
     *         The following special values are defined:
     *         0 means "-113 dBm or less".31 means "-51 dBm or greater".
     */
    public static final String ACTION_SIGNAL_STRENGTH_CHANGED = "android.intent.action.SIG_STR";

    /**
     * Broadcast Action: The radio power state has changed.
     */
    private static final String ACTION_RADIO_POWER_STATE_CHANGED =
            "org.codeaurora.intent.action.RADIO_POWER_STATE";

    private void broadcastServiceStateChanged(ServiceState state, int phoneId, int subId) {
        final long ident = Binder.clearCallingIdentity();
        try {
            mBatteryStats.notePhoneState(state.getState());
        } catch (RemoteException re) {
            // Can't do much
        } finally {
            Binder.restoreCallingIdentity(ident);
        }

        Intent intent = new Intent(Intent.ACTION_SERVICE_STATE);
        intent.addFlags(Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
        Bundle data = new Bundle();
        state.fillInNotifierBundle(data);
        intent.putExtras(data);
        // Pass the subscription along with the intent.
        intent.putExtra(PHONE_CONSTANTS_SUBSCRIPTION_KEY, subId);
        intent.putExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, subId);
        intent.putExtra(PHONE_CONSTANTS_SLOT_KEY, phoneId);
        intent.putExtra(SubscriptionManager.EXTRA_SLOT_INDEX, phoneId);
        // Send the broadcast twice -- once for all apps with READ_PHONE_STATE, then again
        // for all apps with READ_PRIV but not READ_PHONE_STATE. This ensures that any app holding
        // either READ_PRIV or READ_PHONE get this broadcast exactly once.
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL, Manifest.permission.READ_PHONE_STATE);
        mContext.createContextAsUser(UserHandle.ALL, 0)
                .sendBroadcastMultiplePermissions(intent,
                        new String[] { Manifest.permission.READ_PRIVILEGED_PHONE_STATE },
                        new String[] { Manifest.permission.READ_PHONE_STATE });
    }

    private void broadcastRadioPowerStateChanged(int state, int phoneId, int subId) {
        Intent intent = new Intent(ACTION_RADIO_POWER_STATE_CHANGED);
        intent.addFlags(Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
        // Pass the subscription along with the intent.
        intent.putExtra(PHONE_CONSTANTS_SUBSCRIPTION_KEY, subId);
        intent.putExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, subId);
        intent.putExtra(PHONE_CONSTANTS_SLOT_KEY, phoneId);
        intent.putExtra(SubscriptionManager.EXTRA_SLOT_INDEX, phoneId);
        intent.putExtra(PHONE_CONSTANTS_STATE_KEY, state);
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL,
                Manifest.permission.READ_PRIVILEGED_PHONE_STATE);
    }

    private void broadcastSignalStrengthChanged(SignalStrength signalStrength, int phoneId,
            int subId) {
        final long ident = Binder.clearCallingIdentity();
        try {
            mBatteryStats.notePhoneSignalStrength(signalStrength);
        } catch (RemoteException e) {
            /* The remote entity disappeared, we can safely ignore the exception. */
        } finally {
            Binder.restoreCallingIdentity(ident);
        }

        Intent intent = new Intent(ACTION_SIGNAL_STRENGTH_CHANGED);
        Bundle data = new Bundle();
        fillInSignalStrengthNotifierBundle(signalStrength, data);
        intent.putExtras(data);
        intent.putExtra(PHONE_CONSTANTS_SUBSCRIPTION_KEY, subId);
        intent.putExtra(PHONE_CONSTANTS_SLOT_KEY, phoneId);
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    private void fillInSignalStrengthNotifierBundle(SignalStrength signalStrength, Bundle bundle) {
        List<CellSignalStrength> cellSignalStrengths = signalStrength.getCellSignalStrengths();
        for (CellSignalStrength cellSignalStrength : cellSignalStrengths) {
            if (cellSignalStrength instanceof CellSignalStrengthLte) {
                bundle.putParcelable("Lte", (CellSignalStrengthLte) cellSignalStrength);
            } else if (cellSignalStrength instanceof CellSignalStrengthCdma) {
                bundle.putParcelable("Cdma", (CellSignalStrengthCdma) cellSignalStrength);
            } else if (cellSignalStrength instanceof CellSignalStrengthGsm) {
                bundle.putParcelable("Gsm", (CellSignalStrengthGsm) cellSignalStrength);
            } else if (cellSignalStrength instanceof CellSignalStrengthWcdma) {
                bundle.putParcelable("Wcdma", (CellSignalStrengthWcdma) cellSignalStrength);
            } else if (cellSignalStrength instanceof CellSignalStrengthTdscdma) {
                bundle.putParcelable("Tdscdma", (CellSignalStrengthTdscdma) cellSignalStrength);
            } else if (cellSignalStrength instanceof CellSignalStrengthNr) {
                bundle.putParcelable("Nr", (CellSignalStrengthNr) cellSignalStrength);
            }
        }
    }

    /**
     * Broadcasts an intent notifying apps of a phone state change. {@code subId} can be
     * a valid subId, in which case this function fires a subId-specific intent, or it
     * can be {@code SubscriptionManager.INVALID_SUBSCRIPTION_ID}, in which case we send
     * a global state change broadcast ({@code TelephonyManager.ACTION_PHONE_STATE_CHANGED}).
     */
    private void broadcastCallStateChanged(int state, String incomingNumber, int phoneId,
                int subId) {
        final long ident = Binder.clearCallingIdentity();
        try {
            if (state == TelephonyManager.CALL_STATE_IDLE) {
                mBatteryStats.notePhoneOff();
                FrameworkStatsLog.write(FrameworkStatsLog.PHONE_STATE_CHANGED,
                        FrameworkStatsLog.PHONE_STATE_CHANGED__STATE__OFF);
            } else {
                mBatteryStats.notePhoneOn();
                FrameworkStatsLog.write(FrameworkStatsLog.PHONE_STATE_CHANGED,
                        FrameworkStatsLog.PHONE_STATE_CHANGED__STATE__ON);
            }
        } catch (RemoteException e) {
            /* The remote entity disappeared, we can safely ignore the exception. */
        } finally {
            Binder.restoreCallingIdentity(ident);
        }

        Intent intent = new Intent(TelephonyManager.ACTION_PHONE_STATE_CHANGED);
        intent.putExtra(TelephonyManager.EXTRA_STATE, callStateToString(state));

        // If a valid subId was specified, we should fire off a subId-specific state
        // change intent and include the subId.
        if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            intent.setAction(ACTION_SUBSCRIPTION_PHONE_STATE_CHANGED);
            intent.putExtra(PHONE_CONSTANTS_SUBSCRIPTION_KEY, subId);
            intent.putExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, subId);
        }
        // If the phoneId is invalid, the broadcast is for overall call state.
        if (phoneId != SubscriptionManager.INVALID_SIM_SLOT_INDEX) {
            intent.putExtra(PHONE_CONSTANTS_SLOT_KEY, phoneId);
            intent.putExtra(SubscriptionManager.EXTRA_SLOT_INDEX, phoneId);
        }

        // Wakeup apps for the (SUBSCRIPTION_)PHONE_STATE broadcast.
        intent.addFlags(Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);

        // Create a version of the intent with the number always populated.
        Intent intentWithPhoneNumber = new Intent(intent);
        intentWithPhoneNumber.putExtra(TelephonyManager.EXTRA_INCOMING_NUMBER, incomingNumber);

        // Send broadcast twice, once for apps that have PRIVILEGED permission and once for those
        // that have the runtime one
        mContext.sendBroadcastAsUser(intentWithPhoneNumber, UserHandle.ALL,
                android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE);
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL,
                android.Manifest.permission.READ_PHONE_STATE,
                AppOpsManager.OP_READ_PHONE_STATE);
        mContext.sendBroadcastAsUserMultiplePermissions(intentWithPhoneNumber, UserHandle.ALL,
                new String[] { android.Manifest.permission.READ_PHONE_STATE,
                        android.Manifest.permission.READ_CALL_LOG});
    }

    /** Converts TelephonyManager#CALL_STATE_* to TelephonyManager#EXTRA_STATE_*. */
    private static String callStateToString(int callState) {
        switch (callState) {
            case TelephonyManager.CALL_STATE_RINGING:
                return TelephonyManager.EXTRA_STATE_RINGING;
            case TelephonyManager.CALL_STATE_OFFHOOK:
                return TelephonyManager.EXTRA_STATE_OFFHOOK;
            default:
                return TelephonyManager.EXTRA_STATE_IDLE;
        }
    }

    private void broadcastDataConnectionStateChanged(int slotIndex, int subId,
            @NonNull PreciseDataConnectionState pdcs) {
        // Note: not reporting to the battery stats service here, because the
        // status bar takes care of that after taking into account all of the
        // required info.
        Intent intent = new Intent(ACTION_ANY_DATA_CONNECTION_STATE_CHANGED);
        intent.putExtra(PHONE_CONSTANTS_STATE_KEY,
                TelephonyUtils.dataStateToString(pdcs.getState()));
        intent.putExtra(PHONE_CONSTANTS_DATA_APN_KEY, pdcs.getApnSetting().getApnName());
        intent.putExtra(PHONE_CONSTANTS_DATA_APN_TYPE_KEY,
                getApnTypesStringFromBitmask(pdcs.getApnSetting().getApnTypeBitmask()));
        intent.putExtra(PHONE_CONSTANTS_SLOT_KEY, slotIndex);
        intent.putExtra(PHONE_CONSTANTS_SUBSCRIPTION_KEY, subId);
        // Send the broadcast twice -- once for all apps with READ_PHONE_STATE, then again
        // for all apps with READ_PRIV but not READ_PHONE_STATE. This ensures that any app holding
        // either READ_PRIV or READ_PHONE get this broadcast exactly once.
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL, Manifest.permission.READ_PHONE_STATE);
        mContext.createContextAsUser(UserHandle.ALL, 0)
                .sendBroadcastMultiplePermissions(intent,
                        new String[] { Manifest.permission.READ_PRIVILEGED_PHONE_STATE },
                        new String[] { Manifest.permission.READ_PHONE_STATE });
    }

    /**
     * Reimplementation of {@link ApnSetting#getApnTypesStringFromBitmask}.
     */
    @VisibleForTesting
    public static String getApnTypesStringFromBitmask(int apnTypeBitmask) {
        List<String> types = new ArrayList<>();
        int remainingApnTypes = apnTypeBitmask;
        // special case for DEFAULT since it's not a pure bit
        if ((remainingApnTypes & ApnSetting.TYPE_DEFAULT) == ApnSetting.TYPE_DEFAULT) {
            types.add(ApnSetting.TYPE_DEFAULT_STRING);
            remainingApnTypes &= ~ApnSetting.TYPE_DEFAULT;
        }
        while (remainingApnTypes != 0) {
            int highestApnTypeBit = Integer.highestOneBit(remainingApnTypes);
            String apnString = ApnSetting.getApnTypeString(highestApnTypeBit);
            if (!TextUtils.isEmpty(apnString)) types.add(apnString);
            remainingApnTypes &= ~highestApnTypeBit;
        }
        return TextUtils.join(",", types);
    }

    private void enforceNotifyPermissionOrCarrierPrivilege(String method) {
        if (checkNotifyPermission()) {
            return;
        }

        TelephonyPermissions.enforceCallingOrSelfCarrierPrivilege(mContext,
                SubscriptionManager.getDefaultSubscriptionId(), method);
    }

    private boolean checkNotifyPermission(String method) {
        if (checkNotifyPermission()) {
            return true;
        }
        String msg = "Modify Phone State Permission Denial: " + method + " from pid="
                + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid();
        if (DBG) log(msg);
        return false;
    }

    private boolean checkNotifyPermission() {
        return mContext.checkCallingOrSelfPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean checkListenerPermission(Set<Integer> events, int subId, String callingPackage,
            @Nullable String callingFeatureId, String message) {
        LocationAccessPolicy.LocationPermissionQuery.Builder locationQueryBuilder =
                new LocationAccessPolicy.LocationPermissionQuery.Builder()
                        .setCallingPackage(callingPackage)
                        .setCallingFeatureId(callingFeatureId)
                        .setMethod(message + " events: " + events)
                        .setCallingPid(Binder.getCallingPid())
                        .setCallingUid(Binder.getCallingUid());

        boolean shouldCheckLocationPermissions = false;

        if (isLocationPermissionRequired(events)) {
            // Everything that requires fine location started in Q. So far...
            locationQueryBuilder.setMinSdkVersionForFine(Build.VERSION_CODES.Q);
            // If we're enforcing fine starting in Q, we also want to enforce coarse even for
            // older SDK versions.
            locationQueryBuilder.setMinSdkVersionForCoarse(0);
            locationQueryBuilder.setMinSdkVersionForEnforcement(0);
            shouldCheckLocationPermissions = true;
        }

        boolean isPermissionCheckSuccessful = true;

        if (shouldCheckLocationPermissions) {
            LocationAccessPolicy.LocationPermissionResult result =
                    LocationAccessPolicy.checkLocationPermission(
                            mContext, locationQueryBuilder.build());
            switch (result) {
                case DENIED_HARD:
                    throw new SecurityException("Unable to listen for events " + events + " due to "
                            + "insufficient location permissions.");
                case DENIED_SOFT:
                    isPermissionCheckSuccessful = false;
            }
        }

        if (isPhoneStatePermissionRequired(events, callingPackage, Binder.getCallingUserHandle())) {
            if (!TelephonyPermissions.checkCallingOrSelfReadPhoneState(
                    mContext, subId, callingPackage, callingFeatureId, message)) {
                isPermissionCheckSuccessful = false;
            }
        }

        if (isPrecisePhoneStatePermissionRequired(events)) {
            // check if calling app has either permission READ_PRECISE_PHONE_STATE
            // or with carrier privileges
            try {
                mContext.enforceCallingOrSelfPermission(
                        android.Manifest.permission.READ_PRECISE_PHONE_STATE, null);
            } catch (SecurityException se) {
                TelephonyPermissions.enforceCallingOrSelfCarrierPrivilege(mContext, subId, message);
            }
        }

        if (isActiveEmergencySessionPermissionRequired(events)) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.READ_ACTIVE_EMERGENCY_SESSION, null);
        }

        if ((events.contains(TelephonyCallback.EVENT_ALWAYS_REPORTED_SIGNAL_STRENGTH_CHANGED))) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.LISTEN_ALWAYS_REPORTED_SIGNAL_STRENGTH, null);
        }

        if (isPrivilegedPhoneStatePermissionRequired(events)) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE, null);
        }
        return isPermissionCheckSuccessful;
    }

    private void handleRemoveListLocked() {
        int size = mRemoveList.size();
        if (VDBG) log("handleRemoveListLocked: mRemoveList.size()=" + size);
        if (size > 0) {
            for (IBinder b : mRemoveList) {
                remove(b);
            }
            mRemoveList.clear();
        }
    }

    private boolean validateEventAndUserLocked(Record r, int event) {
        int foregroundUser;
        final long callingIdentity = Binder.clearCallingIdentity();
        boolean valid = false;
        try {
            foregroundUser = ActivityManager.getCurrentUser();
            valid = UserHandle.getUserId(r.callerUid) == foregroundUser
                    && r.matchTelephonyCallbackEvent(event);
            if (DBG | DBG_LOC) {
                log("validateEventAndUserLocked: valid=" + valid
                        + " r.callerUid=" + r.callerUid + " foregroundUser=" + foregroundUser
                        + " r.eventList=" + r.eventList + " event=" + event);
            }
        } finally {
            Binder.restoreCallingIdentity(callingIdentity);
        }
        return valid;
    }

    private boolean validatePhoneId(int phoneId) {
        boolean valid = (phoneId >= 0) && (phoneId < mNumPhones);
        if (VDBG) log("validatePhoneId: " + valid);
        return valid;
    }

    private static void log(String s) {
        Rlog.d(TAG, s);
    }

    private static void loge(String s) {
        Rlog.e(TAG, s);
    }

    /**
     * If the registrant specified a subId, then we should only notify it if subIds match.
     * If the registrant registered with DEFAULT subId, we should notify only when the related subId
     * is default subId (which could be INVALID if there's no default subId).
     *
     * This should be the correct way to check record ID match. in idMatch the record's phoneId is
     * speculated based on subId passed by the registrant so it's not a good reference.
     * But to avoid triggering potential regression only replace idMatch with it when an issue with
     * idMatch is reported. Eventually this should replace all instances of idMatch.
     */
    private boolean idMatchWithoutDefaultPhoneCheck(int subIdInRecord, int subIdToNotify) {
        if (subIdInRecord == SubscriptionManager.DEFAULT_SUBSCRIPTION_ID) {
            return (subIdToNotify == mDefaultSubId);
        } else {
            return (subIdInRecord == subIdToNotify);
        }
    }

    boolean idMatch(int rSubId, int subId, int phoneId) {

        if(subId < 0) {
            // Invalid case, we need compare phoneId with default one.
            return (mDefaultPhoneId == phoneId);
        }
        if(rSubId == SubscriptionManager.DEFAULT_SUBSCRIPTION_ID) {
            return (subId == mDefaultSubId);
        } else {
            return (rSubId == subId);
        }
    }

    private boolean checkFineLocationAccess(Record r) {
        return checkFineLocationAccess(r, Build.VERSION_CODES.BASE);
    }

    private boolean checkCoarseLocationAccess(Record r) {
        return checkCoarseLocationAccess(r, Build.VERSION_CODES.BASE);
    }

    /**
     * Note -- this method should only be used at the site of a permission check if you need to
     * explicitly allow apps below a certain SDK level access regardless of location permissions.
     * If you don't need app compat logic, use {@link #checkFineLocationAccess(Record)}.
     */
    private boolean checkFineLocationAccess(Record r, int minSdk) {
        LocationAccessPolicy.LocationPermissionQuery query =
                new LocationAccessPolicy.LocationPermissionQuery.Builder()
                        .setCallingPackage(r.callingPackage)
                        .setCallingFeatureId(r.callingFeatureId)
                        .setCallingPid(r.callerPid)
                        .setCallingUid(r.callerUid)
                        .setMethod("TelephonyRegistry push")
                        .setLogAsInfo(true) // we don't need to log an error every time we push
                        .setMinSdkVersionForFine(minSdk)
                        .setMinSdkVersionForCoarse(minSdk)
                        .setMinSdkVersionForEnforcement(minSdk)
                        .build();

        return Binder.withCleanCallingIdentity(() -> {
            LocationAccessPolicy.LocationPermissionResult locationResult =
                    LocationAccessPolicy.checkLocationPermission(mContext, query);
            return locationResult == LocationAccessPolicy.LocationPermissionResult.ALLOWED;
        });
    }

    /**
     * Note -- this method should only be used at the site of a permission check if you need to
     * explicitly allow apps below a certain SDK level access regardless of location permissions.
     * If you don't need app compat logic, use {@link #checkCoarseLocationAccess(Record)}.
     */
    private boolean checkCoarseLocationAccess(Record r, int minSdk) {
        LocationAccessPolicy.LocationPermissionQuery query =
                new LocationAccessPolicy.LocationPermissionQuery.Builder()
                        .setCallingPackage(r.callingPackage)
                        .setCallingFeatureId(r.callingFeatureId)
                        .setCallingPid(r.callerPid)
                        .setCallingUid(r.callerUid)
                        .setMethod("TelephonyRegistry push")
                        .setLogAsInfo(true) // we don't need to log an error every time we push
                        .setMinSdkVersionForCoarse(minSdk)
                        .setMinSdkVersionForFine(Integer.MAX_VALUE)
                        .setMinSdkVersionForEnforcement(minSdk)
                        .build();

        return Binder.withCleanCallingIdentity(() -> {
            LocationAccessPolicy.LocationPermissionResult locationResult =
                    LocationAccessPolicy.checkLocationPermission(mContext, query);
            return locationResult == LocationAccessPolicy.LocationPermissionResult.ALLOWED;
        });
    }

    private void checkPossibleMissNotify(Record r, int phoneId) {
        Set<Integer> events = r.eventList;

        if (events == null || events.isEmpty()) {
            log("checkPossibleMissNotify: events = null.");
            return;
        }

        if ((events.contains(TelephonyCallback.EVENT_SERVICE_STATE_CHANGED))) {
            try {
                if (VDBG) log("checkPossibleMissNotify: onServiceStateChanged state=" +
                        mServiceState[phoneId]);
                ServiceState ss = new ServiceState(mServiceState[phoneId]);
                if (checkFineLocationAccess(r, Build.VERSION_CODES.Q)) {
                    r.callback.onServiceStateChanged(ss);
                } else if (checkCoarseLocationAccess(r, Build.VERSION_CODES.Q)) {
                    r.callback.onServiceStateChanged(
                            ss.createLocationInfoSanitizedCopy(false));
                } else {
                    r.callback.onServiceStateChanged(
                            ss.createLocationInfoSanitizedCopy(true));
                }
            } catch (RemoteException ex) {
                mRemoveList.add(r.binder);
            }
        }

        if (events.contains(TelephonyCallback.EVENT_SIGNAL_STRENGTHS_CHANGED)
                || events.contains(
                TelephonyCallback.EVENT_ALWAYS_REPORTED_SIGNAL_STRENGTH_CHANGED)) {
            try {
                if (mSignalStrength[phoneId] != null) {
                    SignalStrength signalStrength = mSignalStrength[phoneId];
                    if (DBG) {
                        log("checkPossibleMissNotify: onSignalStrengthsChanged SS="
                                + signalStrength);
                    }
                    r.callback.onSignalStrengthsChanged(new SignalStrength(signalStrength));
                }
            } catch (RemoteException ex) {
                mRemoveList.add(r.binder);
            }
        }

        if (events.contains(TelephonyCallback.EVENT_SIGNAL_STRENGTH_CHANGED)) {
            try {
                if (mSignalStrength[phoneId] != null) {
                    int gsmSignalStrength = mSignalStrength[phoneId]
                            .getGsmSignalStrength();
                    if (DBG) {
                        log("checkPossibleMissNotify: onSignalStrengthChanged SS="
                                + gsmSignalStrength);
                    }
                    r.callback.onSignalStrengthChanged((gsmSignalStrength == 99 ? -1
                            : gsmSignalStrength));
                }
            } catch (RemoteException ex) {
                mRemoveList.add(r.binder);
            }
        }

        if (validateEventAndUserLocked(r, TelephonyCallback.EVENT_CELL_INFO_CHANGED)) {
            try {
                if (DBG_LOC) {
                    log("checkPossibleMissNotify: onCellInfoChanged[" + phoneId + "] = "
                            + mCellInfo.get(phoneId));
                }
                if (checkCoarseLocationAccess(r, Build.VERSION_CODES.BASE)
                        && checkFineLocationAccess(r, Build.VERSION_CODES.Q)) {
                    r.callback.onCellInfoChanged(mCellInfo.get(phoneId));
                }
            } catch (RemoteException ex) {
                mRemoveList.add(r.binder);
            }
        }

        if (events.contains(TelephonyCallback.EVENT_USER_MOBILE_DATA_STATE_CHANGED)) {
            try {
                if (VDBG) {
                    log("checkPossibleMissNotify: onUserMobileDataStateChanged phoneId="
                            + phoneId + " umds=" + mUserMobileDataState[phoneId]);
                }
                r.callback.onUserMobileDataStateChanged(mUserMobileDataState[phoneId]);
            } catch (RemoteException ex) {
                mRemoveList.add(r.binder);
            }
        }

        if (events.contains(TelephonyCallback.EVENT_DISPLAY_INFO_CHANGED)) {
            try {
                if (VDBG) {
                    log("checkPossibleMissNotify: onDisplayInfoChanged phoneId="
                            + phoneId + " dpi=" + mTelephonyDisplayInfos[phoneId]);
                }
                if (mTelephonyDisplayInfos[phoneId] != null) {
                    r.callback.onDisplayInfoChanged(mTelephonyDisplayInfos[phoneId]);
                }
            } catch (RemoteException ex) {
                mRemoveList.add(r.binder);
            }
        }

        if (events.contains(TelephonyCallback.EVENT_MESSAGE_WAITING_INDICATOR_CHANGED)) {
            try {
                if (VDBG) {
                    log("checkPossibleMissNotify: onMessageWaitingIndicatorChanged phoneId="
                            + phoneId + " mwi=" + mMessageWaiting[phoneId]);
                }
                r.callback.onMessageWaitingIndicatorChanged(
                        mMessageWaiting[phoneId]);
            } catch (RemoteException ex) {
                mRemoveList.add(r.binder);
            }
        }

        if (events.contains(TelephonyCallback.EVENT_CALL_FORWARDING_INDICATOR_CHANGED)) {
            try {
                if (VDBG) {
                    log("checkPossibleMissNotify: onCallForwardingIndicatorChanged phoneId="
                        + phoneId + " cfi=" + mCallForwarding[phoneId]);
                }
                r.callback.onCallForwardingIndicatorChanged(
                        mCallForwarding[phoneId]);
            } catch (RemoteException ex) {
                mRemoveList.add(r.binder);
            }
        }

        if (validateEventAndUserLocked(r, TelephonyCallback.EVENT_CELL_LOCATION_CHANGED)) {
            try {
                if (DBG_LOC) {
                    log("checkPossibleMissNotify: onCellLocationChanged mCellIdentity = "
                            + mCellIdentity[phoneId]);
                }
                if (checkCoarseLocationAccess(r, Build.VERSION_CODES.BASE)
                        && checkFineLocationAccess(r, Build.VERSION_CODES.Q)) {
                    // null will be translated to empty CellLocation object in client.
                    r.callback.onCellLocationChanged(mCellIdentity[phoneId]);
                }
            } catch (RemoteException ex) {
                mRemoveList.add(r.binder);
            }
        }

        if (events.contains(TelephonyCallback.EVENT_DATA_CONNECTION_STATE_CHANGED)) {
            try {
                if (DBG) {
                    log("checkPossibleMissNotify: onDataConnectionStateChanged(mDataConnectionState"
                            + "=" + mDataConnectionState[phoneId]
                            + ", mDataConnectionNetworkType=" + mDataConnectionNetworkType[phoneId]
                            + ")");
                }
                r.callback.onDataConnectionStateChanged(mDataConnectionState[phoneId],
                        mDataConnectionNetworkType[phoneId]);
            } catch (RemoteException ex) {
                mRemoveList.add(r.binder);
            }
        }
    }

    /**
     * Returns a string representation of the radio technology (network type)
     * currently in use on the device.
     * @param type for which network type is returned
     * @return the name of the radio technology
     *
     */
    private String getNetworkTypeName(@Annotation.NetworkType int type) {
        switch (type) {
            case TelephonyManager.NETWORK_TYPE_GPRS:
                return "GPRS";
            case TelephonyManager.NETWORK_TYPE_EDGE:
                return "EDGE";
            case TelephonyManager.NETWORK_TYPE_UMTS:
                return "UMTS";
            case TelephonyManager.NETWORK_TYPE_HSDPA:
                return "HSDPA";
            case TelephonyManager.NETWORK_TYPE_HSUPA:
                return "HSUPA";
            case TelephonyManager.NETWORK_TYPE_HSPA:
                return "HSPA";
            case TelephonyManager.NETWORK_TYPE_CDMA:
                return "CDMA";
            case TelephonyManager.NETWORK_TYPE_EVDO_0:
                return "CDMA - EvDo rev. 0";
            case TelephonyManager.NETWORK_TYPE_EVDO_A:
                return "CDMA - EvDo rev. A";
            case TelephonyManager.NETWORK_TYPE_EVDO_B:
                return "CDMA - EvDo rev. B";
            case TelephonyManager.NETWORK_TYPE_1xRTT:
                return "CDMA - 1xRTT";
            case TelephonyManager.NETWORK_TYPE_LTE:
                return "LTE";
            case TelephonyManager.NETWORK_TYPE_EHRPD:
                return "CDMA - eHRPD";
            case TelephonyManager.NETWORK_TYPE_IDEN:
                return "iDEN";
            case TelephonyManager.NETWORK_TYPE_HSPAP:
                return "HSPA+";
            case TelephonyManager.NETWORK_TYPE_GSM:
                return "GSM";
            case TelephonyManager.NETWORK_TYPE_TD_SCDMA:
                return "TD_SCDMA";
            case TelephonyManager.NETWORK_TYPE_IWLAN:
                return "IWLAN";

            //TODO: This network type is marked as hidden because it is not a
            // true network type and we are looking to remove it completely from the available list
            // of network types.  Since this method is only used for logging, in the event that this
            // network type is selected, the log will read as "Unknown."
            //case TelephonyManager.NETWORK_TYPE_LTE_CA:
            //    return "LTE_CA";

            case TelephonyManager.NETWORK_TYPE_NR:
                return "NR";
            default:
                return "UNKNOWN";
        }
    }

    /** Returns a new PreciseCallState object with default values. */
    private static PreciseCallState createPreciseCallState() {
        return new PreciseCallState(PreciseCallState.PRECISE_CALL_STATE_NOT_VALID,
            PreciseCallState.PRECISE_CALL_STATE_NOT_VALID,
            PreciseCallState.PRECISE_CALL_STATE_NOT_VALID,
            DisconnectCause.NOT_VALID,
            PreciseDisconnectCause.NOT_VALID);
    }

    /** Returns a new CallQuality object with default values. */
    private static CallQuality createCallQuality() {
        return new CallQuality(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    private int getPhoneIdFromSubId(int subId) {
        SubscriptionManager subManager = (SubscriptionManager)
                mContext.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
        if (subManager == null) return INVALID_SIM_SLOT_INDEX;

        if (subId == SubscriptionManager.DEFAULT_SUBSCRIPTION_ID) {
            subId = SubscriptionManager.getDefaultSubscriptionId();
        }

        SubscriptionInfo info = subManager.getActiveSubscriptionInfo(subId);
        if (info == null) return INVALID_SIM_SLOT_INDEX;
        return info.getSimSlotIndex();
    }

    /**
     * On certain build types, we should redact information by default. UID information will be
     * preserved in the same log line, so no debugging capability is lost in full bug reports.
     * However, privacy-constrained bug report types (e.g. connectivity) cannot display raw
     * package names on user builds as it's considered an information leak.
     */
    private static String pii(String packageName) {
        return Build.IS_DEBUGGABLE ? packageName : "***";
    }
}
