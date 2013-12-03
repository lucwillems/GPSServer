package com.it4y.gpsserver;

import android.app.Activity;
import android.app.Fragment;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.location.GpsStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.telephony.TelephonyManager;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.util.Log;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.google.analytics.tracking.android.EasyTracker;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

public class GpsServerActivity extends Activity implements LocationListener,GPSStatusInterface {

    private static final SimpleDateFormat timeFormat = new SimpleDateFormat ("yyyy-MM-dd kk:mm:ss");
    private static GPSHTTPServer server=null;
    private static final long TWO_MINUTES=2*60*1000;

    LocationManager lm;
    TelephonyManager tm;
    WifiManager wm;
    ConnectivityManager cm;
    NetworkStateReceiver networkStateReciever=null;
    GpsStatus.Listener gpsListener=null;

    private LocationInfo info=new LocationInfo();
    private boolean useGPS=false;
    private boolean useLock=false;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(Logger.TAG, "onCreate Activity");
        Log.i(Logger.TAG, "Version: " + Version.VERSION);

        setContentView(R.layout.activity_main);
        if (savedInstanceState == null) {
            getFragmentManager().beginTransaction()
                    .add(R.id.container, new GPSStatusFragment())
                    .commit();
        }
        //prevent sleeping...
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);


        wm = (WifiManager) getSystemService(WIFI_SERVICE);
        cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);

        //Register ourself as Location Listener for handling location events
        lm = (LocationManager) this.getSystemService(LOCATION_SERVICE);
        //Cell-ID and WiFi location updates.
        lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,  10*1000, 100, this);

        //We need this for cell information
        tm =(TelephonyManager) this.getSystemService(TELEPHONY_SERVICE);

        //We listen to network state changes so we can keep track of our ip and server
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        networkStateReciever=new NetworkStateReceiver(this);
        registerReceiver(networkStateReciever, filter);

    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.gps_status, menu);
        return true;
    }


    @Override
    public void onStart() {
        Log.i(Logger.TAG,"onStart()");
        super.onStart();
        EasyTracker.getInstance(this).activityStart(this);  // Add this method.
    }

    public void onStop() {
        super.onStop();
        EasyTracker.getInstance(this).activityStop(this);  // Add this method.
    }
    @Override
    public void onRestart() {
        Log.i(Logger.TAG, "onRestart()");
        super.onRestart();
        //get current last know location
        forceUpdateLocation();
    }

    @Override
    public void onPause() {
        Log.i(Logger.TAG, "onPause()");
        super.onPause();
    }

    @Override
    protected void onResume() {
        Log.i(Logger.TAG,"onResume()");
        super.onResume();
        updateLocationManager();
    }

    @Override
    public void onDestroy()
    {
        Log.i(Logger.TAG,"onDestroy()");
        lm.removeUpdates(this);
        unregisterReceiver(networkStateReciever);
        stopServer();
        super.onDestroy();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        Log.i(Logger.TAG,"onOptionsItemSelected"+this.getClass().getSimpleName());
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        switch (item.getItemId()) {
            case R.id.action_update:
                forceUpdateLocation();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }


    protected static boolean isBetterLocation(Location location, Location currentBestLocation) {
        if (currentBestLocation == null) {
            // A new location is always better than no location
            return true;
        }
        if (location == null) {
            // Null location is always bad
            return false;
        }

        // Check whether the new location fix is newer or older
        long timeDelta = location.getTime() - currentBestLocation.getTime();
        boolean isSignificantlyNewer = timeDelta > TWO_MINUTES;
        boolean isSignificantlyOlder = timeDelta < -TWO_MINUTES;
        boolean isNewer = timeDelta > 0;

        // If it's been more than two minutes since the current location, use the new location
        // because the user has likely moved
        if (isSignificantlyNewer) {
            return true;
            // If the new location is more than two minutes older, it must be worse
        } else if (isSignificantlyOlder) {
            return false;
        }

        // Check whether the new location fix is more or less accurate
        int accuracyDelta = (int) (location.getAccuracy() - currentBestLocation.getAccuracy());
        boolean isLessAccurate = accuracyDelta > 0;
        boolean isMoreAccurate = accuracyDelta < 0;
        boolean isSignificantlyLessAccurate = accuracyDelta > 200;

        // Check if the old and new location are from the same provider
        boolean isFromSameProvider = isSameProvider(location.getProvider(),
                currentBestLocation.getProvider());

        // Determine location quality using a combination of timeliness and accuracy
        if (isMoreAccurate) {
            return true;
        } else if (isNewer && !isLessAccurate) {
            return true;
        } else if (isNewer && !isSignificantlyLessAccurate && isFromSameProvider) {
            return true;
        }
        return false;
    }

    /** Checks whether two providers are the same */
    private static boolean isSameProvider(String provider1, String provider2) {
        if (provider1 == null) {
            return provider2 == null;
        }
        return provider1.equals(provider2);
    }

    @Override
    public void onLocationChanged(Location location) {
        if (location != null) {
            Log.i(Logger.TAG, "Location onLocationChanged "+location.getProvider());
            if ( isBetterLocation(location,info.getLocation())) {
                refreshLocation(location);
            } else {
               Log.i(Logger.TAG,"Skipping location("+location.toString()+"), less good");
            }
        } else {
            Log.w(Logger.TAG,"NULL location :-(");
        }
    }

    private void forceUpdateLocation() {
        Location location;
        if (useGPS) {
            Log.i(Logger.TAG,"forceUpdateLocation(GPS)");
            location=lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
        } else {
            Log.i(Logger.TAG,"forceUpdateLocation(NETWORK)");
            location=lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        }
        //refresh all how need to know
        if (location != null) {
            if (isBetterLocation(location,info.getLocation())) {
                refreshLocation(location);
            } else {
                Log.i(Logger.TAG,"Skipping location("+location.toString()+"), less good");
            }
        }
    }

    public void refreshLocation(Location location) {

        Log.i(Logger.TAG, "refresh location");
        if (location == null) {
            Log.w(Logger.TAG,"ignoring NULL location :-(");
            return;
        }
        //update internal information
        info.updateLocation(location);
        info.updateCellInfo(tm);
        //update my server if active
        updateServerLocation();
        //update screen
        updateScreen();
    }

    private void updateServerLocation() {
        //update my server if active
        if (server != null) {
            server.updateLocation(info);
        }
    }

    private void updateScreen() {
        Log.i(Logger.TAG,"update screen");
        TextView textLatitude = (TextView)findViewById(R.id.latitude);
        if (textLatitude == null)
            return;
        TextView textLongitude = (TextView)findViewById(R.id.longitude);
        TextView textspeed = (TextView)findViewById(R.id.speed);
        TextView textHeading = (TextView)findViewById(R.id.heading);
        TextView textAccurancy = (TextView)findViewById(R.id.accurancy);
        TextView textLocationTime=(TextView)findViewById(R.id.locationTime);
        TextView textLocationProvider=(TextView)findViewById(R.id.locationProvider);
        TextView textCellOperator=(TextView)findViewById(R.id.cellOperator);
        TextView textCellMCCMNC=(TextView)findViewById(R.id.cellMCCMNC);
        TextView textCelllac=(TextView)findViewById(R.id.cellLac);
        TextView textCellcid=(TextView)findViewById(R.id.cellCid);
        TextView textIP=(TextView)findViewById(R.id.wifiIP);
        TextView textGPSLocked=(TextView)findViewById(R.id.satlocked);
        TextView textGPSLockTime=(TextView)findViewById(R.id.satlockTime);
        TextView textGPSSatellite=(TextView)findViewById(R.id.satellites);
        TextView textVersion=(TextView)findViewById(R.id.apkversion);


        textLatitude.setText(Double.toString(info.getLat()));
        textLongitude.setText(Double.toString(info.getLon()));
        textspeed.setText(Float.toString(info.getSpeed()));
        textHeading.setText(Float.toString(info.getHeading()));
        textAccurancy.setText(Float.toString(info.getAccuracy()));
        textLocationProvider.setText(info.getProvider());
        textLocationTime.setText(timeFormat.format(new Date(info.getLocationTime()*1000)));
        textCellOperator.setText(info.getCellOperator());
        textCellMCCMNC.setText(info.getMccmnc());
        textCelllac.setText(Integer.toString(info.getLac()));
        textCellcid.setText(Integer.toString(info.getCid()));
        if (info.getWifiState()) {
            textIP.setText("http://"+info.getIP()+":"+GPSHTTPServer.serverPort);
        } else {
            textIP.setText("no IP/Wifi active");
        }
        textGPSLocked.setText(Boolean.toString(info.getGPSLocked()));
        textGPSLockTime.setText(Long.toString(info.getGPSFirstFixtime()));
        textGPSSatellite.setText(Long.toString(info.getGPSLockedSatellites())+"/"+Long.toString(info.getGPSTotalSatellites()));
        //app version
        try {
        PackageInfo manager=getPackageManager().getPackageInfo(getPackageName(), 0);
            textVersion.setText(manager.packageName+" "+manager.versionName+"["+manager.versionCode+"]");
        } catch (PackageManager.NameNotFoundException e) {
            textVersion.setText("??");
        }
    }

    @Override
    public void onStatusChanged(String s, int status, Bundle bundle) {
    }

    @Override
    public void onProviderEnabled(String s) {
        Log.i(Logger.TAG, "Location onProviderEnabled " + s);
    }

    @Override
    public void onProviderDisabled(String s) {
        Log.i(Logger.TAG, "location onProviderDisabled " + s);
    }

    public void onWifiConnected() {
        Log.i(Logger.TAG,"Wifi connected");
        info.updateWifi(cm, wm);
        startServer();
        updateScreen();
        updateServerLocation();
        Toast.makeText(getBaseContext(), " Wifi on ", Toast.LENGTH_LONG).show();
    }

    public void onWifiDisconnected() {
        Log.i(Logger.TAG,"Wifi disconnect");
        info.updateWifi(cm, wm);
        stopServer();
        Toast.makeText(getBaseContext(), " Wifi off", Toast.LENGTH_LONG).show();
        updateScreen();
        updateServerLocation();
    }

    public void onNetworkChange() {
        forceUpdateLocation();
    }

    public void updateLocationManager() {
        lm.removeUpdates(this);
        if (gpsListener != null) {
            lm.removeGpsStatusListener(gpsListener);
            gpsListener=null;
        }
        if (useGPS) {
            Log.i(Logger.TAG,"using GPS+network for location");
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0, 0, this);
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER,0,0, this);
            gpsListener=new GpsStatus.Listener() {
                @Override
                public void onGpsStatusChanged(int event) {
                    Log.i(Logger.TAG, "onGpsStatusChanged: " + event);
                    info.updateGPS(event, lm);
                    updateServerLocation();
                    updateScreen();
                }
            };
            lm.addGpsStatusListener(gpsListener);
            Toast.makeText(getBaseContext(),"Using GPS now", Toast.LENGTH_LONG).show();
        } else {
            //don't use GPS anymore
            lm.removeUpdates(this);
            Log.i(Logger.TAG,"using network for location");
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0, 0, this);
        }
    }


    @Override
    public void onToggleGPS(Boolean state) {
      //when state changes
      Log.i(Logger.TAG, "onToggleGPS " + state);
      useGPS=state;
      updateLocationManager();
    }

    @Override
    public void onToggleLock(Boolean state) {
        //TODO

    }

    public void startServer() {
        //startup HTTP server
        if (server == null) {
            server=new GPSHTTPServer();
            try {
                server.start();
                server.updateLocation(info);
            } catch (IOException e) {
                Log.e(Logger.TAG,"HTTP error",e);
            }
        } else {
            Log.w(Logger.TAG,"HTTP server already active ?");
        }
    }

    public void stopServer() {
        //Stop server if no wifi
        if (server != null) {
            server.stop();
            server=null;
            Log.i(Logger.TAG, "HTTP server stopped");
        } else {
            Log.w(Logger.TAG, "NO HTTP server active");
        }
    }
    /**
     * A placeholder fragment containing a simple view.
     */
    public static class GPSStatusFragment extends Fragment {
        GPSStatusInterface mCallback;

        public GPSStatusFragment() {
        }

        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
            Log.i(Logger.TAG,"onCreateView "+this.getClass().getSimpleName());
            View rootView = inflater.inflate(R.layout.fragment_main, container, false);
            return rootView;
        }

        @Override
        public void onAttach(Activity activity) {
            super.onAttach(activity);
            Log.i(Logger.TAG,"onAttach "+this.getClass().getSimpleName());
            try {
                mCallback = (GPSStatusInterface) activity;
            } catch (ClassCastException e) {
                //ignore
            }
        }

        @Override
        public void onActivityCreated(Bundle savedInstanceState) {
            Log.i(Logger.TAG,"onActivityCreated "+this.getClass().getSimpleName());
            super.onActivityCreated(savedInstanceState);
            //GPS switch listener
            Switch useGPSButton = (Switch) getView().findViewById(R.id.gpsSwitch);
            useGPSButton.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {

                @Override
                public void onCheckedChanged(CompoundButton buttonView,boolean isChecked) {
                    if (mCallback!=null)
                        mCallback.onToggleGPS(isChecked);
                }
            });

/*
            //Lock switch listener
            Switch useLockButton = (Switch) getView().findViewById(R.id.lockSwitch);
            useLockButton.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {

                @Override
                public void onCheckedChanged(CompoundButton buttonView,boolean isChecked) {
  //                  ((GpsServerActivity)getActivity()).onToggleLock(isChecked);
                }
            });
*/

        }

    }

    public static class NetworkStateReceiver extends BroadcastReceiver {
        private GpsServerActivity activity;

        public NetworkStateReceiver(GpsServerActivity activity) {
            this.activity=activity;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                Log.i(Logger.TAG,"Broadcast msg: "+action);
                //WIFI changes
                if(action.equals(WifiManager.NETWORK_STATE_CHANGED_ACTION)){
                    NetworkInfo WifiInfo = intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
                    if (WifiInfo != null) {
                        if (WifiInfo.isConnected()) {
                            activity.onWifiConnected();
                        } else if ( !WifiInfo.isConnected() && !WifiInfo.isConnectedOrConnecting()) {
                            activity.onWifiDisconnected();
                        }
                    }

                }
                //Network changes
                if(action.equals(ConnectivityManager.CONNECTIVITY_ACTION)){
                    activity.onNetworkChange();
                }
        }

    }

}
