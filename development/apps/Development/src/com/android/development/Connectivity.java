/* //device/apps/Settings/src/com/android/settings/Keyguard.java
**
** Copyright 2006, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

package com.android.development;

import android.app.Activity;
import android.app.ActivityManagerNative;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.NetworkUtils;
import android.net.RouteInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiActivityEnergyInfo;
import android.net.wifi.WifiManager;
import android.os.RemoteException;
import android.os.Handler;
import android.os.Message;
import android.os.IBinder;
import android.os.INetworkManagementService;
import android.os.Parcel;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.ServiceManager;
import android.os.ServiceManagerNative;
import android.os.SystemClock;
import android.provider.Settings;
import android.os.Bundle;
import android.util.Log;
import android.view.IWindowManager;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.AdapterView.OnItemSelectedListener;

import com.android.internal.telephony.Phone;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;

import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.params.ConnRouteParams;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpParams;
import org.apache.http.HttpResponse;
import org.apache.http.impl.client.DefaultHttpClient;

public class Connectivity extends Activity {
    private static final String TAG = "DevTools - Connectivity";
    private static final String GET_SCAN_RES = "Get Results";
    private static final String START_SCAN = "Start Scan";
    private static final String PROGRESS_SCAN = "In Progress";

    private static final long SCAN_CYCLES = 15;

    private static final int EVENT_TOGGLE_WIFI = 1;
    private static final int EVENT_TOGGLE_SCREEN = 2;

    private EditText mDCOnDurationEdit;
    private EditText mDCOffDurationEdit;
    private TextView mDCCycleCountView;
    private long mDCOnDuration = 120000;
    private long mDCOffDuration = 120000;
    private int mDCCycleCount = 0;

    private EditText mSCOnDurationEdit;
    private EditText mSCOffDurationEdit;
    private TextView mSCCycleCountView;
    private long mSCOnDuration = 120000;
    private long mSCOffDuration = 12000;
    private int mSCCycleCount = 0;

    private boolean mDelayedCycleStarted = false;

    private Button mScanButton;
    private TextView mScanResults;
    private EditText mScanCyclesEdit;
    private CheckBox mScanDisconnect;
    private long mScanCycles = SCAN_CYCLES;
    private long mScanCur = -1;
    private long mStartTime = -1;
    private long mStopTime;
    private long mTotalScanTime = 0;
    private long mTotalScanCount = 0;

    private TextView mLinkStatsResults;

    private String mTdlsAddr = null;

    private WifiManager mWm;
    private PowerManager mPm;
    private ConnectivityManager mCm;
    private INetworkManagementService mNetd;

    private WifiScanReceiver mScanRecv;
    IntentFilter mIntentFilter;

    private WakeLock mWakeLock = null;
    private WakeLock mScreenonWakeLock = null;

    private boolean mScreenOffToggleRunning = false;
    private boolean mScreenOff = false;

    private static final String CONNECTIVITY_TEST_ALARM =
            "com.android.development.CONNECTIVITY_TEST_ALARM";
    private static final String TEST_ALARM_EXTRA = "CONNECTIVITY_TEST_EXTRA";
    private static final String TEST_ALARM_ON_EXTRA = "CONNECTIVITY_TEST_ON_EXTRA";
    private static final String TEST_ALARM_OFF_EXTRA = "CONNECTIVITY_TEST_OFF_EXTRA";
    private static final String TEST_ALARM_CYCLE_EXTRA = "CONNECTIVITY_TEST_CYCLE_EXTRA";
    private static final String SCREEN_ON = "SCREEN_ON";
    private static final String SCREEN_OFF = "SCREEN_OFF";
    public BroadcastReceiver mReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(CONNECTIVITY_TEST_ALARM)) {
                String extra = (String)intent.getExtra(TEST_ALARM_EXTRA);
                PowerManager pm = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
                Long on = new Long(120000);
                Long off = new Long(120000);
                int cycle = 0;
                try {
                    on = Long.parseLong((String)intent.getExtra(TEST_ALARM_ON_EXTRA));
                    off = Long.parseLong((String)intent.getExtra(TEST_ALARM_OFF_EXTRA));
                    cycle = Integer.parseInt((String)intent.getExtra(TEST_ALARM_CYCLE_EXTRA));
                } catch (Exception e) {}

                if (extra.equals(SCREEN_ON)) {
                    mScreenonWakeLock = mPm.newWakeLock(PowerManager.FULL_WAKE_LOCK |
                            PowerManager.ACQUIRE_CAUSES_WAKEUP,
                            "ConnectivityTest");
                    mScreenonWakeLock.acquire();

                    mSCCycleCount = cycle+1;
                    mSCOnDuration = on;
                    mSCOffDuration = off;
                    mSCCycleCountView.setText(Integer.toString(mSCCycleCount));

                    scheduleAlarm(mSCOnDuration, SCREEN_OFF);
                } else if (extra.equals(SCREEN_OFF)) {

                    mSCCycleCount = cycle;
                    mSCOnDuration = on;
                    mSCOffDuration = off;

                    mScreenonWakeLock.release();
                    mScreenonWakeLock = null;
                    scheduleAlarm(mSCOffDuration, SCREEN_ON);
                    pm.goToSleep(SystemClock.uptimeMillis());
                }
            }
        }
    };

    public Handler mHandler2 = new Handler() {
        public void handleMessage(Message msg) {
            switch(msg.what) {
                case EVENT_TOGGLE_WIFI:
                    Log.e(TAG, "EVENT_TOGGLE_WIFI");
                    if (mDelayedCycleStarted && mWm != null) {
                        long delay;
                        switch (mWm.getWifiState()) {
                            case WifiManager.WIFI_STATE_ENABLED:
                            case WifiManager.WIFI_STATE_ENABLING:
                                mWm.setWifiEnabled(false);
                                delay = mDCOffDuration;
                                break;
                            default:
                                mWm.setWifiEnabled(true);
                                delay = mDCOnDuration;
                                mDCCycleCount++;
                                mDCCycleCountView.setText(Integer.toString(mDCCycleCount));
                        }
                        sendMessageDelayed(obtainMessage(EVENT_TOGGLE_WIFI),
                                delay);
                    }
                    break;
            }
        }
    };

   /**
     * Wifi Scan Listener
     */
    private class WifiScanReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (action.equals(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)) {
                mStopTime = SystemClock.elapsedRealtime();
                if (mStartTime != -1) {
                    mTotalScanTime += (mStopTime - mStartTime);
                    mStartTime = -1;
                }
                Log.d(TAG, "Scan: READY " + mScanCur);
                mScanResults.setVisibility(View.INVISIBLE);

                List<ScanResult> wifiScanResults = mWm.getScanResults();
                if (wifiScanResults != null) {
                    mTotalScanCount += wifiScanResults.size();
                    mScanResults.setText("Current scan = " + Long.toString(wifiScanResults.size()));
                    mScanResults.setVisibility(View.VISIBLE);
                    Log.d(TAG, "Scan: Results = " + wifiScanResults.size());
                }

                mScanCur--;
                mScanCyclesEdit.setText(Long.toString(mScanCur));
                if (mScanCur == 0) {
                    unregisterReceiver(mScanRecv);
                    mScanButton.setText(GET_SCAN_RES);
                    mScanResults.setVisibility(View.INVISIBLE);
                } else {
                    Log.d(TAG, "Scan: START " + mScanCur);
                    mStartTime = SystemClock.elapsedRealtime();
                    mWm.startScan();
                }
            }
        }
    }

    private static class DevToolsNetworkCallback extends NetworkCallback {
        private static final String TAG = "DevToolsNetworkCallback";

        public void onPreCheck(Network network) {
            Log.d(TAG, "onPreCheck: " + network.netId);
        }

        public void onAvailable(Network network) {
            Log.d(TAG, "onAvailable: " + network.netId);
        }

        public void onCapabilitiesChanged(Network network, NetworkCapabilities nc) {
            Log.d(TAG, "onCapabilitiesChanged: " + network.netId + " " + nc.toString());
        }

        public void onLinkPropertiesChanged(Network network, LinkProperties lp) {
            Log.d(TAG, "onLinkPropertiesChanged: " + network.netId + " " + lp.toString());
        }

        public void onLosing(Network network, int maxMsToLive) {
            Log.d(TAG, "onLosing: " + network.netId + " " + maxMsToLive);
        }

        public void onLost(Network network) {
            Log.d(TAG, "onLost: " + network.netId);
        }
    }
    private DevToolsNetworkCallback mCallback;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        setContentView(R.layout.connectivity);

        mWm = (WifiManager)getSystemService(Context.WIFI_SERVICE);
        mPm = (PowerManager)getSystemService(Context.POWER_SERVICE);
        mCm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        IBinder b = ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE);
        mNetd = INetworkManagementService.Stub.asInterface(b);

        findViewById(R.id.enableWifi).setOnClickListener(mClickListener);
        findViewById(R.id.disableWifi).setOnClickListener(mClickListener);

        findViewById(R.id.startDelayedCycle).setOnClickListener(mClickListener);
        findViewById(R.id.stopDelayedCycle).setOnClickListener(mClickListener);
        mDCOnDurationEdit = (EditText)findViewById(R.id.dc_wifi_on_duration);
        mDCOnDurationEdit.setText(Long.toString(mDCOnDuration));
        mDCOffDurationEdit = (EditText)findViewById(R.id.dc_wifi_off_duration);
        mDCOffDurationEdit.setText(Long.toString(mDCOffDuration));
        mDCCycleCountView = (TextView)findViewById(R.id.dc_wifi_cycles_done);
        mDCCycleCountView.setText(Integer.toString(mDCCycleCount));

        findViewById(R.id.startScreenCycle).setOnClickListener(mClickListener);
        findViewById(R.id.stopScreenCycle).setOnClickListener(mClickListener);
        mSCOnDurationEdit = (EditText)findViewById(R.id.sc_wifi_on_duration);
        mSCOnDurationEdit.setText(Long.toString(mSCOnDuration));
        mSCOffDurationEdit = (EditText)findViewById(R.id.sc_wifi_off_duration);
        mSCOffDurationEdit.setText(Long.toString(mSCOffDuration));
        mSCCycleCountView = (TextView)findViewById(R.id.sc_wifi_cycles_done);
        mSCCycleCountView.setText(Integer.toString(mSCCycleCount));

        mScanButton = (Button)findViewById(R.id.startScan);
        mScanButton.setOnClickListener(mClickListener);
        mScanCyclesEdit = (EditText)findViewById(R.id.sc_scan_cycles);
        mScanCyclesEdit.setText(Long.toString(mScanCycles));
        mScanDisconnect = (CheckBox)findViewById(R.id.scanDisconnect);
        mScanDisconnect.setChecked(true);
        mScanResults = (TextView)findViewById(R.id.sc_scan_results);
        mScanResults.setVisibility(View.INVISIBLE);

        mScanRecv = new WifiScanReceiver();
        mIntentFilter = new IntentFilter();
        mIntentFilter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);

        findViewById(R.id.startTdls).setOnClickListener(mClickListener);
        findViewById(R.id.stopTdls).setOnClickListener(mClickListener);

        findViewById(R.id.start_mms).setOnClickListener(mClickListener);
        findViewById(R.id.stop_mms).setOnClickListener(mClickListener);
        findViewById(R.id.start_hipri).setOnClickListener(mClickListener);
        findViewById(R.id.stop_hipri).setOnClickListener(mClickListener);
        findViewById(R.id.report_all_bad).setOnClickListener(mClickListener);
        findViewById(R.id.crash).setOnClickListener(mClickListener);

        findViewById(R.id.add_default_route).setOnClickListener(mClickListener);
        findViewById(R.id.remove_default_route).setOnClickListener(mClickListener);
        findViewById(R.id.bound_http_request).setOnClickListener(mClickListener);
        findViewById(R.id.bound_socket_request).setOnClickListener(mClickListener);
        findViewById(R.id.routed_http_request).setOnClickListener(mClickListener);
        findViewById(R.id.routed_socket_request).setOnClickListener(mClickListener);
        findViewById(R.id.default_request).setOnClickListener(mClickListener);
        findViewById(R.id.default_socket).setOnClickListener(mClickListener);
        findViewById(R.id.link_stats).setOnClickListener(mClickListener);

        registerReceiver(mReceiver, new IntentFilter(CONNECTIVITY_TEST_ALARM));

        mLinkStatsResults = (TextView)findViewById(R.id.stats);
        mLinkStatsResults.setVisibility(View.VISIBLE);

        NetworkRequest.Builder builder = new NetworkRequest.Builder();
        for (int i = 0; i < 255; i++) {
            try { builder.removeCapability(i); } catch (IllegalArgumentException e) {}
        }
        NetworkRequest request = builder.build();
        mCallback = new DevToolsNetworkCallback();
        mCm.registerNetworkCallback(request, mCallback);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mCm.unregisterNetworkCallback(mCallback);
        unregisterReceiver(mReceiver);
    }

    @Override
    public void onResume() {
        super.onResume();
        findViewById(R.id.connectivity_layout).requestFocus();
    }

    private View.OnClickListener mClickListener = new View.OnClickListener() {
        public void onClick(View v) {
            switch (v.getId()) {
                case R.id.enableWifi:
                    mWm.setWifiEnabled(true);
                    break;
                case R.id.disableWifi:
                    mWm.setWifiEnabled(false);
                    break;
                case R.id.startDelayedCycle:
                    onStartDelayedCycle();
                    break;
                case R.id.stopDelayedCycle:
                    onStopDelayedCycle();
                    break;
                case R.id.startScreenCycle:
                    onStartScreenCycle();
                    break;
                case R.id.stopScreenCycle:
                    onStopScreenCycle();
                    break;
                case R.id.startScan:
                    onStartScanCycle();
                    break;
                case R.id.startTdls:
                    onStartTdls();
                    break;
                case R.id.stopTdls:
                    onStopTdls();
                    break;
                case R.id.start_mms:
                    mCm.startUsingNetworkFeature(ConnectivityManager.TYPE_MOBILE,
                            Phone.FEATURE_ENABLE_MMS);
                    break;
                case R.id.stop_mms:
                    mCm.stopUsingNetworkFeature(ConnectivityManager.TYPE_MOBILE,
                            Phone.FEATURE_ENABLE_MMS);
                    break;
                case R.id.default_socket:
                    onDefaultSocket();
                    break;
                case R.id.default_request:
                    onDefaultRequest();
                    break;
                case R.id.routed_socket_request:
                    onRoutedSocketRequest();
                    break;
                case R.id.routed_http_request:
                    onRoutedHttpRequest();
                    break;
                case R.id.bound_socket_request:
                    onBoundSocketRequest();
                    break;
                case R.id.bound_http_request:
                    onBoundHttpRequest();
                    break;
                case R.id.remove_default_route:
                    onRemoveDefaultRoute();
                    break;
                case R.id.add_default_route:
                    onAddDefaultRoute();
                    break;
                case R.id.report_all_bad:
                    onReportAllBad();
                    break;
                case R.id.crash:
                    onCrash();
                    break;
                case R.id.start_hipri:
                    mCm.startUsingNetworkFeature(ConnectivityManager.TYPE_MOBILE,
                            Phone.FEATURE_ENABLE_HIPRI);
                    break;
                case R.id.stop_hipri:
                    mCm.stopUsingNetworkFeature(ConnectivityManager.TYPE_MOBILE,
                            Phone.FEATURE_ENABLE_HIPRI);
                    break;
                case R.id.link_stats:
                    onLinkStats();
                    break;
            }
        }
    };


    private void onStartDelayedCycle() {
        if (!mDelayedCycleStarted) {
            mDelayedCycleStarted = true;
            try {
                mDCOnDuration = Long.parseLong(mDCOnDurationEdit.getText().toString());
                mDCOffDuration = Long.parseLong(mDCOffDurationEdit.getText().toString());
            } catch (Exception e) { };
            mDCCycleCount = 0;

            mWakeLock = mPm.newWakeLock(PowerManager.FULL_WAKE_LOCK, "ConnectivityTest");
            mWakeLock.acquire();
            mHandler2.sendMessage(mHandler2.obtainMessage(EVENT_TOGGLE_WIFI));
        }
    }

    private void onStopDelayedCycle() {
        if (mDelayedCycleStarted) {
            mDelayedCycleStarted = false;
            mWakeLock.release();
            mWakeLock = null;
            if(mHandler2.hasMessages(EVENT_TOGGLE_WIFI)) {
                mHandler2.removeMessages(EVENT_TOGGLE_WIFI);
            }
        }
    }

    private void onStartScreenCycle() {
        try {
            mSCOnDuration = Long.parseLong(mSCOnDurationEdit.getText().toString());
            mSCOffDuration = Long.parseLong(mSCOffDurationEdit.getText().toString());
        } catch (Exception e) { };
        mSCCycleCount = 0;

        mScreenonWakeLock = mPm.newWakeLock(PowerManager.FULL_WAKE_LOCK,
                "ConnectivityTest");
        mScreenonWakeLock.acquire();

        scheduleAlarm(10, SCREEN_OFF);
    }

    private void scheduleAlarm(long delayMs, String eventType) {
        AlarmManager am = (AlarmManager)getSystemService(Context.ALARM_SERVICE);
        Intent i = new Intent(CONNECTIVITY_TEST_ALARM);

        i.putExtra(TEST_ALARM_EXTRA, eventType);
        i.putExtra(TEST_ALARM_ON_EXTRA, Long.toString(mSCOnDuration));
        i.putExtra(TEST_ALARM_OFF_EXTRA, Long.toString(mSCOffDuration));
        i.putExtra(TEST_ALARM_CYCLE_EXTRA, Integer.toString(mSCCycleCount));

        PendingIntent p = PendingIntent.getBroadcast(this, 0, i, PendingIntent.FLAG_UPDATE_CURRENT);

        am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delayMs, p);
    }

    private void onStopScreenCycle() {
    }

    private void onReportAllBad() {
        Network[] networks = mCm.getAllNetworks();
        for (Network network : networks) {
            mCm.reportBadNetwork(network);
        }
    }

    private void onCrash() {
        ConnectivityManager foo = null;
        foo.startUsingNetworkFeature(ConnectivityManager.TYPE_MOBILE,
                Phone.FEATURE_ENABLE_MMS);
    }

    private void onStartScanCycle() {
        if (mScanCur == -1) {
            try {
                mScanCur = Long.parseLong(mScanCyclesEdit.getText().toString());
                mScanCycles = mScanCur;
            } catch (Exception e) { };
            if (mScanCur <= 0) {
                mScanCur = -1;
                mScanCycles = SCAN_CYCLES;
                return;
            }
        }
        if (mScanCur > 0) {
            registerReceiver(mScanRecv, mIntentFilter);
            mScanButton.setText(PROGRESS_SCAN);
            mScanResults.setVisibility(View.INVISIBLE);
            if (mScanDisconnect.isChecked())
                mWm.disconnect();
            mTotalScanTime = 0;
            mTotalScanCount = 0;
            Log.d(TAG, "Scan: START " + mScanCur);
            mStartTime = SystemClock.elapsedRealtime();
            mWm.startScan();
        } else {
            // Show results
            mScanResults.setText("Average Scan Time = " +
                Long.toString(mTotalScanTime / mScanCycles) + " ms ; Average Scan Amount = " +
                Long.toString(mTotalScanCount / mScanCycles));
            mScanResults.setVisibility(View.VISIBLE);
            mScanButton.setText(START_SCAN);
            mScanCur = -1;
            mScanCyclesEdit.setText(Long.toString(mScanCycles));
            if (mScanDisconnect.isChecked())
                mWm.reassociate();
        }
    }

    private void onStartTdls() {
        mTdlsAddr = ((EditText)findViewById(R.id.sc_ip_mac)).getText().toString();
        Log.d(TAG, "TDLS: START " + mTdlsAddr);
        InetAddress inetAddress = null;
        try {
            inetAddress = InetAddress.getByName(mTdlsAddr);
            mWm.setTdlsEnabled(inetAddress, true);
        } catch (Exception e) {
            mWm.setTdlsEnabledWithMacAddress(mTdlsAddr, true);
        }
    }

    private void onStopTdls() {
        if (mTdlsAddr == null) return;
        Log.d(TAG, "TDLS: STOP " + mTdlsAddr);
        InetAddress inetAddress = null;
        try {
            inetAddress = InetAddress.getByName(mTdlsAddr);
            mWm.setTdlsEnabled(inetAddress, false);
        } catch (Exception e) {
            mWm.setTdlsEnabledWithMacAddress(mTdlsAddr, false);
        }
    }

    private void onLinkStats() {
        Log.e(TAG, "LINK STATS:  ");
        try {
            WifiActivityEnergyInfo info =
                    mWm.getControllerActivityEnergyInfo(0);
            if (info != null) {
                mLinkStatsResults.setText(" power " + info.toString());
            } else {
                mLinkStatsResults.setText(" null! ");
            }
        } catch (Exception e) {
            mLinkStatsResults.setText(" failed! " + e.toString());
        }
    }


    private void onAddDefaultRoute() {
        try {
            int netId = Integer.valueOf(((TextView) findViewById(R.id.netid)).getText().toString());
            mNetd.addRoute(netId, new RouteInfo((LinkAddress) null,
                    NetworkUtils.numericToInetAddress("8.8.8.8")));
        } catch (Exception e) {
            Log.e(TAG, "onAddDefaultRoute got exception: " + e.toString());
        }
    }

    private void onRemoveDefaultRoute() {
        try {
            int netId = Integer.valueOf(((TextView) findViewById(R.id.netid)).getText().toString());
            mNetd.removeRoute(netId, new RouteInfo((LinkAddress) null,
                    NetworkUtils.numericToInetAddress("8.8.8.8")));
        } catch (Exception e) {
            Log.e(TAG, "onRemoveDefaultRoute got exception: " + e.toString());
        }
    }

    private void onRoutedHttpRequest() {
        onRoutedRequest(HTTP);
    }

    private void onRoutedSocketRequest() {
        onRoutedRequest(SOCKET);
    }

    private final static int SOCKET = 1;
    private final static int HTTP   = 2;

    private void onRoutedRequest(int type) {
        String url = "www.google.com";

        InetAddress inetAddress = null;
        try {
            inetAddress = InetAddress.getByName(url);
        } catch (Exception e) {
            Log.e(TAG, "error fetching address for " + url);
            return;
        }

        mCm.requestRouteToHostAddress(ConnectivityManager.TYPE_MOBILE_HIPRI, inetAddress);

        switch (type) {
            case SOCKET:
                onBoundSocketRequest();
                break;
            case HTTP:
                HttpGet get = new HttpGet("http://" + url);
                HttpClient client = new DefaultHttpClient();
                try {
                    HttpResponse httpResponse = client.execute(get);
                    Log.d(TAG, "routed http request gives " + httpResponse.getStatusLine());
                } catch (Exception e) {
                    Log.e(TAG, "routed http request exception = " + e);
                }
        }

    }

    private void onBoundHttpRequest() {
        NetworkInterface networkInterface = null;
        try {
            networkInterface = NetworkInterface.getByName("rmnet0");
            Log.d(TAG, "networkInterface is " + networkInterface);
        } catch (Exception e) {
            Log.e(TAG, " exception getByName: " + e);
            return;
        }
        if (networkInterface != null) {
            Enumeration inetAddressess = networkInterface.getInetAddresses();
            while(inetAddressess.hasMoreElements()) {
                Log.d(TAG, " inetAddress:" + ((InetAddress)inetAddressess.nextElement()));
            }
        }

        HttpParams httpParams = new BasicHttpParams();
        if (networkInterface != null) {
            ConnRouteParams.setLocalAddress(httpParams,
                    networkInterface.getInetAddresses().nextElement());
        }
        HttpGet get = new HttpGet("http://www.bbc.com");
        HttpClient client = new DefaultHttpClient(httpParams);
        try {
            HttpResponse response = client.execute(get);
            Log.d(TAG, "response code = " + response.getStatusLine());
        } catch (Exception e) {
            Log.e(TAG, "Exception = "+ e );
        }
    }

    private void onBoundSocketRequest() {
        NetworkInterface networkInterface = null;
        try {
            networkInterface = NetworkInterface.getByName("rmnet0");
        } catch (Exception e) {
            Log.e(TAG, "exception getByName: " + e);
            return;
        }
        if (networkInterface == null) {
            try {
                Log.d(TAG, "getting any networkInterface");
                networkInterface = NetworkInterface.getNetworkInterfaces().nextElement();
            } catch (Exception e) {
                Log.e(TAG, "exception getting any networkInterface: " + e);
                return;
            }
        }
        if (networkInterface == null) {
            Log.e(TAG, "couldn't find a local interface");
            return;
        }
        Enumeration inetAddressess = networkInterface.getInetAddresses();
        while(inetAddressess.hasMoreElements()) {
            Log.d(TAG, " addr:" + ((InetAddress)inetAddressess.nextElement()));
        }
        InetAddress local = null;
        InetAddress remote = null;
        try {
            local = networkInterface.getInetAddresses().nextElement();
        } catch (Exception e) {
            Log.e(TAG, "exception getting local InetAddress: " + e);
            return;
        }
        try {
            remote = InetAddress.getByName("www.flickr.com");
        } catch (Exception e) {
            Log.e(TAG, "exception getting remote InetAddress: " + e);
            return;
        }
        Log.d(TAG, "remote addr ="+remote);
        Log.d(TAG, "local addr ="+local);
        Socket socket = null;
        try {
            socket = new Socket(remote, 80, local, 6000);
        } catch (Exception e) {
            Log.e(TAG, "Exception creating socket: " + e);
            return;
        }
        try {
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            out.println("Hi flickr");
        } catch (Exception e) {
            Log.e(TAG, "Exception writing to socket: " + e);
            return;
        }
    }

    private void onDefaultRequest() {
        HttpParams params = new BasicHttpParams();
        HttpGet get = new HttpGet("http://www.cnn.com");
        HttpClient client = new DefaultHttpClient(params);
        try {
            HttpResponse response = client.execute(get);
            Log.e(TAG, "response code = " + response.getStatusLine());
        } catch (Exception e) {
            Log.e(TAG, "Exception = " + e);
        }
    }

    private void onDefaultSocket() {
        InetAddress remote = null;
        try {
            remote = InetAddress.getByName("www.flickr.com");
        } catch (Exception e) {
            Log.e(TAG, "exception getting remote InetAddress: " + e);
            return;
        }
        Log.e(TAG, "remote addr =" + remote);
        Socket socket = null;
        try {
            socket = new Socket(remote, 80);
        } catch (Exception e) {
            Log.e(TAG, "Exception creating socket: " + e);
            return;
        }
        try {
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            out.println("Hi flickr");
            Log.e(TAG, "written");
        } catch (Exception e) {
            Log.e(TAG, "Exception writing to socket: " + e);
            return;
        }
    }
}
