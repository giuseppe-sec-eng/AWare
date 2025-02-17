/**
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package android.app.usage.cts;

import android.app.AppOpsManager;
import android.app.usage.NetworkStatsManager;
import android.app.usage.NetworkStats;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.telephony.TelephonyManager;
import android.test.InstrumentationTestCase;
import android.util.Log;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.text.MessageFormat;
import java.util.Scanner;
import java.net.HttpURLConnection;

import libcore.io.IoUtils;
import libcore.io.Streams;

public class NetworkUsageStatsTest extends InstrumentationTestCase {
    private static final String LOG_TAG = "NetworkUsageStatsTest";
    private static final String APPOPS_SET_SHELL_COMMAND = "appops set {0} {1} {2}";
    private static final String APPOPS_GET_SHELL_COMMAND = "appops get {0} {1}";

    private static final long MINUTE = 1000 * 60;

    private static final int[] sNetworkTypesToTest = new int[] {
        ConnectivityManager.TYPE_WIFI,
        ConnectivityManager.TYPE_MOBILE,
    };

    private static final String[] sSystemFeaturesToTest = new String[] {
        PackageManager.FEATURE_WIFI,
        PackageManager.FEATURE_TELEPHONY,
    };

    private static final String[] sFeatureNotConnectedCause = new String[] {
        " Please make sure you are connected to a WiFi access point.",
        " Please make sure you have added a SIM card with data plan to your phone, have enabled " +
                "data over cellular and in case of dual SIM devices, have selected the right SIM " +
                "for data connection."
    };

    private NetworkStatsManager mNsm;
    private ConnectivityManager mCm;
    private PackageManager mPm;
    private long mStartTime;
    private long mEndTime;

    private long mBytesRead;
    private String mWriteSettingsMode;
    private String mUsageStatsMode;

    private void exerciseRemoteHost(Network network) throws Exception {
        final int timeout = 15000;
        NetworkInfo networkInfo = mCm.getNetworkInfo(network);
        if (networkInfo == null) {
            Log.w(LOG_TAG, "Network info is null");
        } else {
            Log.w(LOG_TAG, "Network: " + networkInfo.toString());
        }
        InputStreamReader in = null;
        HttpURLConnection urlc = null;
        String originalKeepAlive = System.getProperty("http.keepAlive");
        System.setProperty("http.keepAlive", "false");
        try {
            urlc = (HttpURLConnection) network.openConnection(new URL(
                    "http://www.265.com/"));
            urlc.setConnectTimeout(timeout);
            urlc.setUseCaches(false);
            urlc.connect();
            boolean ping = urlc.getResponseCode() == 200;
            if (ping) {
                in = new InputStreamReader(
                        (InputStream) urlc.getContent());

                mBytesRead = 0;
                while (in.read() != -1) ++mBytesRead;
            }
        } catch (Exception e) {
            Log.i(LOG_TAG, "Badness during exercising remote server: " + e);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                    // don't care
                }
            }
            if (urlc != null) {
                urlc.disconnect();
            }
            if (originalKeepAlive == null) {
                System.clearProperty("http.keepAlive");
            } else {
                System.setProperty("http.keepAlive", originalKeepAlive);
            }
        }
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mNsm = (NetworkStatsManager) getInstrumentation().getContext()
                .getSystemService(Context.NETWORK_STATS_SERVICE);

        mCm = (ConnectivityManager) getInstrumentation().getContext()
                .getSystemService(Context.CONNECTIVITY_SERVICE);

        mPm = getInstrumentation().getContext().getPackageManager();

        mWriteSettingsMode = getAppOpsMode(AppOpsManager.OPSTR_WRITE_SETTINGS);
        setAppOpsMode(AppOpsManager.OPSTR_WRITE_SETTINGS, "allow");
        mUsageStatsMode = getAppOpsMode(AppOpsManager.OPSTR_GET_USAGE_STATS);
    }

    @Override
    protected void tearDown() throws Exception {
        if (mWriteSettingsMode != null) {
            setAppOpsMode(AppOpsManager.OPSTR_WRITE_SETTINGS, mWriteSettingsMode);
        }
        if (mUsageStatsMode != null) {
            setAppOpsMode(AppOpsManager.OPSTR_GET_USAGE_STATS, mUsageStatsMode);
        }
        super.tearDown();
    }

    private void setAppOpsMode(String appop, String mode) throws Exception {
        final String command = MessageFormat.format(APPOPS_SET_SHELL_COMMAND,
                getInstrumentation().getContext().getPackageName(), appop, mode);
        ParcelFileDescriptor pfd = getInstrumentation().getUiAutomation()
                .executeShellCommand(command);
        try {
            Streams.readFully(new FileInputStream(pfd.getFileDescriptor()));
        } finally {
            IoUtils.closeQuietly(pfd.getFileDescriptor());
        }
    }

    private String getAppOpsMode(String appop) throws Exception {
        String result;
        final String command = MessageFormat.format(APPOPS_GET_SHELL_COMMAND,
                getInstrumentation().getContext().getPackageName(), appop);
        ParcelFileDescriptor pfd = getInstrumentation().getUiAutomation()
                .executeShellCommand(command);
        try {
            result = convertStreamToString(new FileInputStream(pfd.getFileDescriptor()));
        } finally {
            IoUtils.closeQuietly(pfd.getFileDescriptor());
        }
        if (result == null) {
            Log.w(LOG_TAG, "App op " + appop + " could not be read.");
        }
        return result;
    }

    private static String convertStreamToString(InputStream is) {
        try (Scanner scanner = new Scanner(is).useDelimiter("\\A")) {
            return scanner.hasNext() ? scanner.next() : null;
        }
    }

    private boolean shouldTestThisNetworkType(int networkTypeIndex, long tolerance)
            throws Exception {
        Network[] networks = mCm.getAllNetworks();
        for (Network network : networks) {
            NetworkInfo networkInfo = mCm.getNetworkInfo(network);
            if (networkInfo != null && networkInfo.isConnected() &&
                    networkInfo.getType() == sNetworkTypesToTest[networkTypeIndex]) {
                NetworkCapabilities capabilities = mCm.getNetworkCapabilities(network);
                if (capabilities != null && capabilities.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    mStartTime = System.currentTimeMillis() - tolerance;
                    exerciseRemoteHost(network);
                    mEndTime = System.currentTimeMillis() + tolerance;
                    return true;
                }
            }
        }
        assertFalse (sSystemFeaturesToTest[networkTypeIndex] + " is a reported system feature, " +
                "however no corresponding connected network interface was found. " +
                sFeatureNotConnectedCause[networkTypeIndex],
                mPm.hasSystemFeature(sSystemFeaturesToTest[networkTypeIndex]));
        return false;
    }

    private String getSubscriberId(int networkType) {
        if (ConnectivityManager.TYPE_MOBILE == networkType) {
            TelephonyManager tm = (TelephonyManager) getInstrumentation().getContext()
                    .getSystemService(Context.TELEPHONY_SERVICE);
            return tm.getSubscriberId();
        }
        return "";
    }

    public void testDeviceSummary() throws Exception {
        for (int i = 0; i < sNetworkTypesToTest.length; ++i) {
            if (!shouldTestThisNetworkType(i, MINUTE/2)) {
                continue;
            }
            setAppOpsMode(AppOpsManager.OPSTR_GET_USAGE_STATS, "allow");
            NetworkStats.Bucket bucket = null;
            try {
                bucket = mNsm.querySummaryForDevice(
                        sNetworkTypesToTest[i], getSubscriberId(sNetworkTypesToTest[i]),
                        mStartTime, mEndTime);
            } catch (RemoteException | SecurityException e) {
                fail("testDeviceSummary fails with exception: " + e.toString());
            }
            assertNotNull(bucket);
            assertTimestamps(bucket);
            assertEquals(bucket.getState(), NetworkStats.Bucket.STATE_ALL);
            assertEquals(bucket.getUid(), NetworkStats.Bucket.UID_ALL);
            setAppOpsMode(AppOpsManager.OPSTR_GET_USAGE_STATS, "deny");
            try {
                bucket = mNsm.querySummaryForDevice(
                        sNetworkTypesToTest[i], getSubscriberId(sNetworkTypesToTest[i]),
                        mStartTime, mEndTime);
                fail("negative testDeviceSummary fails: no exception thrown.");
            } catch (RemoteException e) {
                fail("testDeviceSummary fails with exception: " + e.toString());
            } catch (SecurityException e) {
                // expected outcome
            }
        }
    }

    public void testUserSummary() throws Exception {
        for (int i = 0; i < sNetworkTypesToTest.length; ++i) {
            if (!shouldTestThisNetworkType(i, MINUTE/2)) {
                continue;
            }
            setAppOpsMode(AppOpsManager.OPSTR_GET_USAGE_STATS, "allow");
            NetworkStats.Bucket bucket = null;
            try {
                bucket = mNsm.querySummaryForUser(
                        sNetworkTypesToTest[i], getSubscriberId(sNetworkTypesToTest[i]),
                        mStartTime, mEndTime);
            } catch (RemoteException | SecurityException e) {
                fail("testUserSummary fails with exception: " + e.toString());
            }
            assertNotNull(bucket);
            assertTimestamps(bucket);
            assertEquals(bucket.getState(), NetworkStats.Bucket.STATE_ALL);
            assertEquals(bucket.getUid(), NetworkStats.Bucket.UID_ALL);
            setAppOpsMode(AppOpsManager.OPSTR_GET_USAGE_STATS, "deny");
            try {
                bucket = mNsm.querySummaryForUser(
                        sNetworkTypesToTest[i], getSubscriberId(sNetworkTypesToTest[i]),
                        mStartTime, mEndTime);
                fail("negative testUserSummary fails: no exception thrown.");
            } catch (RemoteException e) {
                fail("testUserSummary fails with exception: " + e.toString());
            } catch (SecurityException e) {
                // expected outcome
            }
        }
    }

    public void testAppSummary() throws Exception {
        for (int i = 0; i < sNetworkTypesToTest.length; ++i) {
            if (!shouldTestThisNetworkType(i, MINUTE/2)) {
                continue;
            }
            setAppOpsMode(AppOpsManager.OPSTR_GET_USAGE_STATS, "allow");
            NetworkStats result = null;
            try {
                result = mNsm.querySummary(
                        sNetworkTypesToTest[i], getSubscriberId(sNetworkTypesToTest[i]),
                        mStartTime, mEndTime);
                assertTrue(result != null);
                NetworkStats.Bucket bucket = new NetworkStats.Bucket();
                long totalTxPackets = 0;
                long totalRxPackets = 0;
                long totalTxBytes = 0;
                long totalRxBytes = 0;
                while (result.hasNextBucket()) {
                    assertTrue(result.getNextBucket(bucket));
                    assertTimestamps(bucket);
                    if (bucket.getUid() == Process.myUid()) {
                        totalTxPackets += bucket.getTxPackets();
                        totalRxPackets += bucket.getRxPackets();
                        totalTxBytes += bucket.getTxBytes();
                        totalRxBytes += bucket.getRxBytes();
                    }
                }
                assertFalse(result.getNextBucket(bucket));
                assertTrue("No Rx bytes usage for uid " + Process.myUid(), totalRxBytes > 0);
                assertTrue("No Rx packets usage for uid " + Process.myUid(), totalRxPackets > 0);
                assertTrue("No Tx bytes usage for uid " + Process.myUid(), totalTxBytes > 0);
                assertTrue("No Tx packets usage for uid " + Process.myUid(), totalTxPackets > 0);
            } catch (RemoteException | SecurityException e) {
                fail("testAppSummary fails with exception: " + e.toString());
            } finally {
                if (result != null) {
                    result.close();
                }
            }
            setAppOpsMode(AppOpsManager.OPSTR_GET_USAGE_STATS, "deny");
            try {
                result = mNsm.querySummary(
                        sNetworkTypesToTest[i], getSubscriberId(sNetworkTypesToTest[i]),
                        mStartTime, mEndTime);
                fail("negative testAppSummary fails: no exception thrown.");
            } catch (RemoteException e) {
                fail("testAppSummary fails with exception: " + e.toString());
            } catch (SecurityException e) {
                // expected outcome
            }
        }
    }

    public void testAppDetails() throws Exception {
        for (int i = 0; i < sNetworkTypesToTest.length; ++i) {
            // Relatively large tolerance to accommodate for history bucket size.
            if (!shouldTestThisNetworkType(i, MINUTE * 120)) {
                continue;
            }
            setAppOpsMode(AppOpsManager.OPSTR_GET_USAGE_STATS, "allow");
            NetworkStats result = null;
            try {
                result = mNsm.queryDetails(
                        sNetworkTypesToTest[i], getSubscriberId(sNetworkTypesToTest[i]),
                        mStartTime, mEndTime);
                assertTrue(result != null);
                NetworkStats.Bucket bucket = new NetworkStats.Bucket();
                long totalTxPackets = 0;
                long totalRxPackets = 0;
                long totalTxBytes = 0;
                long totalRxBytes = 0;
                while (result.hasNextBucket()) {
                    assertTrue(result.getNextBucket(bucket));
                    assertTimestamps(bucket);
                    assertEquals(bucket.getState(), NetworkStats.Bucket.STATE_ALL);
                    if (bucket.getUid() == Process.myUid()) {
                        totalTxPackets += bucket.getTxPackets();
                        totalRxPackets += bucket.getRxPackets();
                        totalTxBytes += bucket.getTxBytes();
                        totalRxBytes += bucket.getRxBytes();
                    }
                }
                assertFalse(result.getNextBucket(bucket));
                assertTrue("No Rx bytes usage for uid " + Process.myUid(), totalRxBytes > 0);
                assertTrue("No Rx packets usage for uid " + Process.myUid(), totalRxPackets > 0);
                assertTrue("No Tx bytes usage for uid " + Process.myUid(), totalTxBytes > 0);
                assertTrue("No Tx packets usage for uid " + Process.myUid(), totalTxPackets > 0);
            } catch (RemoteException | SecurityException e) {
                fail("testAppDetails fails with exception: " + e.toString());
            } finally {
                if (result != null) {
                    result.close();
                }
            }
            setAppOpsMode(AppOpsManager.OPSTR_GET_USAGE_STATS, "deny");
            try {
                result = mNsm.queryDetails(
                        sNetworkTypesToTest[i], getSubscriberId(sNetworkTypesToTest[i]),
                        mStartTime, mEndTime);
                fail("negative testAppDetails fails: no exception thrown.");
            } catch (RemoteException e) {
                fail("testAppDetails fails with exception: " + e.toString());
            } catch (SecurityException e) {
                // expected outcome
            }
        }
    }

    public void testUidDetails() throws Exception {
        for (int i = 0; i < sNetworkTypesToTest.length; ++i) {
            // Relatively large tolerance to accommodate for history bucket size.
            if (!shouldTestThisNetworkType(i, MINUTE * 120)) {
                continue;
            }
            setAppOpsMode(AppOpsManager.OPSTR_GET_USAGE_STATS, "allow");
            NetworkStats result = null;
            try {
                result = mNsm.queryDetailsForUid(
                        sNetworkTypesToTest[i], getSubscriberId(sNetworkTypesToTest[i]),
                        mStartTime, mEndTime, Process.myUid());
                assertTrue(result != null);
                NetworkStats.Bucket bucket = new NetworkStats.Bucket();
                long totalTxPackets = 0;
                long totalRxPackets = 0;
                long totalTxBytes = 0;
                long totalRxBytes = 0;
                while (result.hasNextBucket()) {
                    assertTrue(result.getNextBucket(bucket));
                    assertTimestamps(bucket);
                    assertEquals(bucket.getState(), NetworkStats.Bucket.STATE_ALL);
                    assertEquals(bucket.getUid(), Process.myUid());
                    totalTxPackets += bucket.getTxPackets();
                    totalRxPackets += bucket.getRxPackets();
                    totalTxBytes += bucket.getTxBytes();
                    totalRxBytes += bucket.getRxBytes();
                }
                assertFalse(result.getNextBucket(bucket));
                assertTrue("No Rx bytes usage for uid " + Process.myUid(), totalRxBytes > 0);
                assertTrue("No Rx packets usage for uid " + Process.myUid(), totalRxPackets > 0);
                assertTrue("No Tx bytes usage for uid " + Process.myUid(), totalTxBytes > 0);
                assertTrue("No Tx packets usage for uid " + Process.myUid(), totalTxPackets > 0);
            } catch (RemoteException | SecurityException e) {
                fail("testUidDetails fails with exception: " + e.toString());
            } finally {
                if (result != null) {
                    result.close();
                }
            }
            setAppOpsMode(AppOpsManager.OPSTR_GET_USAGE_STATS, "deny");
            try {
                result = mNsm.queryDetailsForUid(
                        sNetworkTypesToTest[i], getSubscriberId(sNetworkTypesToTest[i]),
                        mStartTime, mEndTime, Process.myUid());
                fail("negative testUidDetails fails: no exception thrown.");
            } catch (RemoteException e) {
                fail("testUidDetails fails with exception: " + e.toString());
            } catch (SecurityException e) {
                // expected outcome
            }
        }
    }

    private void assertTimestamps(final NetworkStats.Bucket bucket) {
        assertTrue("Start timestamp " + bucket.getStartTimeStamp() + " is less than " +
                mStartTime, bucket.getStartTimeStamp() >= mStartTime);
        assertTrue("End timestamp " + bucket.getEndTimeStamp() + " is greater than " +
                mEndTime, bucket.getEndTimeStamp() <= mEndTime);
    }
}
