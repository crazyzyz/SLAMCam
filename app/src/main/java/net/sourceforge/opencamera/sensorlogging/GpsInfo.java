package net.sourceforge.opencamera.sensorlogging;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.core.content.ContextCompat;

import net.sourceforge.opencamera.MainActivity;
import net.sourceforge.opencamera.MyDebug;
import net.sourceforge.opencamera.StorageUtils;
import net.sourceforge.opencamera.StorageUtilsWrapper;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Date;

/**
 * Handles GPS location recording during video capture.
 * Records latitude, longitude, altitude, accuracy, speed, and timestamp to a
 * CSV file.
 * Uses the same clock source as IMU sensors for synchronization.
 */
public class GpsInfo implements LocationListener {
    private static final String TAG = "GpsInfo";
    private static final String CSV_SEPARATOR = ",";
    private static final String SENSOR_NAME = "gps";

    // Minimum time between GPS updates in milliseconds
    private static final long MIN_TIME_MS = 100; // 10 Hz max
    // Minimum distance between GPS updates in meters
    private static final float MIN_DISTANCE_M = 0.0f;

    private final Context mContext;
    private final LocationManager mLocationManager;
    private PrintWriter mGpsWriter;
    private File mLastGpsFile;
    private boolean mIsRecording;
    private boolean mIsListening;

    public GpsInfo(Context context) {
        mContext = context;
        mLocationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
    }

    /**
     * Check if GPS is available on this device
     */
    public boolean isGpsAvailable() {
        return mLocationManager != null &&
                mLocationManager.getAllProviders().contains(LocationManager.GPS_PROVIDER);
    }

    /**
     * Check if we have location permissions
     */
    public boolean hasLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            boolean hasFineLocation = ContextCompat.checkSelfPermission(mContext,
                    Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            boolean hasCoarseLocation = ContextCompat.checkSelfPermission(mContext,
                    Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            return hasFineLocation || hasCoarseLocation;
        }
        return true; // Pre-Marshmallow, permissions are granted at install time
    }

    /**
     * Start listening to GPS updates
     */
    public boolean enableGps() {
        if (MyDebug.LOG) {
            Log.d(TAG, "enableGps");
        }

        if (!isGpsAvailable()) {
            if (MyDebug.LOG) {
                Log.e(TAG, "GPS provider not available");
            }
            return false;
        }

        if (!hasLocationPermission()) {
            if (MyDebug.LOG) {
                Log.e(TAG, "Location permission not granted");
            }
            return false;
        }

        try {
            // Request GPS updates
            if (mLocationManager.getAllProviders().contains(LocationManager.GPS_PROVIDER)) {
                mLocationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        MIN_TIME_MS,
                        MIN_DISTANCE_M,
                        this);
                mIsListening = true;
                if (MyDebug.LOG) {
                    Log.d(TAG, "GPS listener registered");
                }
            }

            // Also request network location as fallback
            if (mLocationManager.getAllProviders().contains(LocationManager.NETWORK_PROVIDER)) {
                mLocationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        MIN_TIME_MS,
                        MIN_DISTANCE_M,
                        this);
                if (MyDebug.LOG) {
                    Log.d(TAG, "Network location listener registered");
                }
            }

            return true;
        } catch (SecurityException e) {
            if (MyDebug.LOG) {
                Log.e(TAG, "SecurityException when requesting location updates", e);
            }
            return false;
        }
    }

    /**
     * Stop listening to GPS updates
     */
    public void disableGps() {
        if (MyDebug.LOG) {
            Log.d(TAG, "disableGps");
        }

        if (mIsListening) {
            try {
                mLocationManager.removeUpdates(this);
                mIsListening = false;
                if (MyDebug.LOG) {
                    Log.d(TAG, "GPS listeners removed");
                }
            } catch (SecurityException e) {
                if (MyDebug.LOG) {
                    Log.e(TAG, "SecurityException when removing location updates", e);
                }
            }
        }
    }

    /**
     * Start recording GPS data to a CSV file
     */
    public void startRecording(MainActivity mainActivity, Date currentVideoDate) {
        if (MyDebug.LOG) {
            Log.d(TAG, "startRecording");
        }

        mLastGpsFile = null;

        try {
            FileWriter gpsFileWriter = getGpsInfoFileWriter(mainActivity, currentVideoDate);
            mGpsWriter = new PrintWriter(new BufferedWriter(gpsFileWriter));

            // Write CSV header
            mGpsWriter.write("latitude,longitude,altitude,accuracy,speed,bearing,timestamp_ns\n");

            mIsRecording = true;
            if (MyDebug.LOG) {
                Log.d(TAG, "GPS recording started");
            }
        } catch (IOException e) {
            if (MyDebug.LOG) {
                Log.e(TAG, "Failed to start GPS recording", e);
            }
            e.printStackTrace();
        }
    }

    /**
     * Stop recording GPS data
     */
    public void stopRecording() {
        if (MyDebug.LOG) {
            Log.d(TAG, "stopRecording");
        }

        mIsRecording = false;

        if (mGpsWriter != null) {
            mGpsWriter.flush();
            mGpsWriter.close();
            mGpsWriter = null;
            if (MyDebug.LOG) {
                Log.d(TAG, "GPS recording stopped");
            }
        }
    }

    /**
     * Check if currently recording
     */
    public boolean isRecording() {
        return mIsRecording;
    }

    /**
     * Get the last recorded GPS file
     */
    public File getLastGpsFile() {
        return mLastGpsFile;
    }

    /**
     * Create the GPS CSV file
     */
    private FileWriter getGpsInfoFileWriter(MainActivity mainActivity, Date lastVideoDate) throws IOException {
        StorageUtilsWrapper storageUtils = mainActivity.getStorageUtils();
        FileWriter fileWriter;

        try {
            if (storageUtils.isUsingSAF()) {
                Uri saveUri = storageUtils.createOutputCaptureInfoFileSAF(
                        StorageUtils.MEDIA_TYPE_RAW_SENSOR_INFO, SENSOR_NAME, "csv", lastVideoDate);
                ParcelFileDescriptor gpsPfd = mainActivity
                        .getContentResolver()
                        .openFileDescriptor(saveUri, "w");
                if (gpsPfd != null) {
                    fileWriter = new FileWriter(gpsPfd.getFileDescriptor());
                    File saveFile = storageUtils.getFileFromDocumentUriSAF(saveUri, false);
                    storageUtils.broadcastFile(saveFile, true, false, true);
                    mLastGpsFile = saveFile;
                } else {
                    throw new IOException("File descriptor was null");
                }
            } else {
                File saveFile = storageUtils.createOutputCaptureInfoFile(
                        StorageUtils.MEDIA_TYPE_RAW_SENSOR_INFO, SENSOR_NAME, "csv", lastVideoDate);
                fileWriter = new FileWriter(saveFile);
                if (MyDebug.LOG) {
                    Log.d(TAG, "save to: " + saveFile.getAbsolutePath());
                }
                mLastGpsFile = saveFile;
                storageUtils.broadcastFile(saveFile, false, false, false);
            }
            return fileWriter;
        } catch (IOException e) {
            e.printStackTrace();
            if (MyDebug.LOG) {
                Log.e(TAG, "Failed to open GPS info file");
            }
            throw new IOException(e);
        }
    }

    // LocationListener callbacks

    @Override
    public void onLocationChanged(Location location) {
        if (mIsRecording && mGpsWriter != null && location != null) {
            // Use elapsedRealtimeNanos for synchronization with IMU sensors (available
            // since API 17)
            long timestampNs;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                timestampNs = location.getElapsedRealtimeNanos();
            } else {
                // Fallback: convert system time to approximate nanoseconds
                timestampNs = location.getTime() * 1000000L;
            }

            StringBuilder gpsData = new StringBuilder();
            gpsData.append(location.getLatitude()).append(CSV_SEPARATOR);
            gpsData.append(location.getLongitude()).append(CSV_SEPARATOR);
            gpsData.append(location.hasAltitude() ? location.getAltitude() : 0.0).append(CSV_SEPARATOR);
            gpsData.append(location.hasAccuracy() ? location.getAccuracy() : 0.0f).append(CSV_SEPARATOR);
            gpsData.append(location.hasSpeed() ? location.getSpeed() : 0.0f).append(CSV_SEPARATOR);
            gpsData.append(location.hasBearing() ? location.getBearing() : 0.0f).append(CSV_SEPARATOR);
            gpsData.append(timestampNs).append("\n");

            mGpsWriter.write(gpsData.toString());

            if (MyDebug.LOG) {
                Log.d(TAG, "GPS data recorded: lat=" + location.getLatitude() +
                        ", lon=" + location.getLongitude() + ", ts=" + timestampNs);
            }
        }
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
        if (MyDebug.LOG) {
            Log.d(TAG, "onStatusChanged: provider=" + provider + ", status=" + status);
        }
    }

    @Override
    public void onProviderEnabled(String provider) {
        if (MyDebug.LOG) {
            Log.d(TAG, "onProviderEnabled: " + provider);
        }
    }

    @Override
    public void onProviderDisabled(String provider) {
        if (MyDebug.LOG) {
            Log.d(TAG, "onProviderDisabled: " + provider);
        }
    }
}
