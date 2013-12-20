package com.it4y.gpsserver;

import android.app.Activity;
import android.app.Fragment;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
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
import com.google.gson.Gson;

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

    protected LocationInfo info=new LocationInfo();
    private boolean useGPS=false;
    private boolean useTrack=false;


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
        //We need this for cell information
        tm =(TelephonyManager) this.getSystemService(TELEPHONY_SERVICE);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.gps_status, menu);
        return true;
    }


    @Override
    public void onStart() {
        Log.i(Logger.TAG, "onStart()");
        super.onStart();
        EasyTracker.getInstance(this).activityStart(this);  // Add this method.
    }

    @Override
    public void onRestart() {
        Log.i(Logger.TAG, "onRestart()");
        super.onRestart();
        EasyTracker.getInstance(this).activityStart(this);  // Add this method.
    }

    public void onStop() {
        super.onStop();
        EasyTracker.getInstance(this).activityStop(this);  // Add this method.
        //where not active anymore so lets stop draining battery
        lm.removeUpdates(this);
    }

    @Override
    public void onPause() {
        Log.i(Logger.TAG, "onPause()");
        SharedPreferences mPrefs=getSharedPreferences(getApplicationInfo().name, Context.MODE_PRIVATE);
        SharedPreferences.Editor ed=mPrefs.edit();
        Log.i(Logger.TAG,"store "+info.getTrackSize()+" records track data");
        ed.putString("trackData",info.trackToJSON());
        ed.commit();
        removeEventHandling();
        stopServer();
        super.onPause();
    }

    @Override
    protected void onResume() {
        Log.i(Logger.TAG, "onResume()");
        super.onResume();
        SharedPreferences mPrefs=getSharedPreferences(getApplicationInfo().name, Context.MODE_PRIVATE);
        String track=mPrefs.getString("trackData",null);
        if (track != null) {
            info.trackFromJSON(track);
            Log.i(Logger.TAG,"loaded "+info.getTrackSize()+" records track data");
        }
        initEventHandling();
    }

    @Override
    public void onDestroy()
    {
        Log.i(Logger.TAG, "onDestroy()");
        super.onDestroy();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        Log.d(Logger.TAG,"onOptionsItemSelected"+this.getClass().getSimpleName());
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

        //case we get CELL and it is less accuracy then last gps
        if (location.getProvider().equalsIgnoreCase("network") &&
            currentBestLocation.getProvider().equalsIgnoreCase("gps") &&
            location.getAccuracy() < currentBestLocation.distanceTo(location))
            return false;

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
            Log.d(Logger.TAG, "Location onLocationChanged "+location.getProvider());
            synchronized (info) {
              if ( isBetterLocation(location,info.getLocation())) {
                   refreshLocation(location);
              } else {
                 Log.i(Logger.TAG,"Skipping location("+location.toString()+"), less good");
              }
            }
        } else {
            Log.w(Logger.TAG, "NULL location :-(");
        }
    }

    private void forceUpdateLocation() {
        Location location;
        if (useGPS) {
            Log.d(Logger.TAG,"forceUpdateLocation(GPS)");
            location=lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
        } else {
            Log.d(Logger.TAG,"forceUpdateLocation(NETWORK)");
            location=lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        }
        //refresh all how need to know
        synchronized (info) {
            if (location != null) {
                if (isBetterLocation(location,info.getLocation())) {
                    refreshLocation(location);
                } else {
                    Log.i(Logger.TAG,"Skipping location("+location.toString()+"), less good");
                }
            }
        }
    }

    public void refreshLocation(Location location) {

        Log.d(Logger.TAG, "refresh location");
        if (location == null) {
            Log.w(Logger.TAG,"ignoring NULL location :-(");
            return;
        }
        synchronized (info) {
            //update internal information
            info.updateLocation(location);
            info.updateCellInfo(tm);
        }
        //update screen
        updateScreen();
    }

    private void updateScreen() {
        Log.d(Logger.TAG,"update screen");
        TextView textLatitude = (TextView)findViewById(R.id.latitude);
        if (textLatitude == null)
            return;
        TextView textLongitude = (TextView)findViewById(R.id.longitude);
        TextView textspeed = (TextView)findViewById(R.id.speed);
        TextView textHeading = (TextView)findViewById(R.id.heading);
        TextView textAccurancy = (TextView)findViewById(R.id.accurancy);
        TextView textLocationTime=(TextView)findViewById(R.id.locationTime);
        TextView textLocationProvider=(TextView)findViewById(R.id.locationProvider);
        TextView textTrackerSize=(TextView)findViewById(R.id.trackSize);
        TextView textCellOperator=(TextView)findViewById(R.id.cellOperator);
        TextView textCellMCCMNC=(TextView)findViewById(R.id.cellMCCMNC);
        TextView textCelllac=(TextView)findViewById(R.id.cellLac);
        TextView textCellcid=(TextView)findViewById(R.id.cellCid);
        TextView textIP=(TextView)findViewById(R.id.wifiIP);
        TextView textGPSLocked=(TextView)findViewById(R.id.satlocked);
        TextView textGPSLockTime=(TextView)findViewById(R.id.satlockTime);
        TextView textGPSSatellite=(TextView)findViewById(R.id.satellites);
        TextView textVersion=(TextView)findViewById(R.id.apkversion);

        //lock info RO
        //i know this is lazy.bad,etc...
        synchronized (info) {
          if (info.getLocation() != null ) {
              Location location=info.getLocation();
              textLatitude.setText(Double.toString(location.getLatitude()));
              textLongitude.setText(Double.toString(location.getLongitude()));
              textspeed.setText(Float.toString(location.getSpeed()));
              textHeading.setText(Float.toString(location.getBearing()));
              textAccurancy.setText(Float.toString(location.getAccuracy()));
              textLocationProvider.setText(location.getProvider());
              textLocationTime.setText(timeFormat.format(new Date(location.getTime())));
              textTrackerSize.setText(info.getTrackSize()+"/"+info.MAX_RECORDS);
          }
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
        }

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
        synchronized (info) {
           info.updateWifi(cm, wm);
        }
        startServer();
        updateScreen();
        Toast.makeText(getBaseContext(), " Wifi on ", Toast.LENGTH_LONG).show();
    }

    public void onWifiDisconnected() {
        Log.i(Logger.TAG,"Wifi disconnect");
        synchronized (info) {
            info.updateWifi(cm, wm);
        }
        stopServer();
        Toast.makeText(getBaseContext(), " Wifi off", Toast.LENGTH_LONG).show();
        updateScreen();
    }

    public void onNetworkChange() {
        Log.d(Logger.TAG,"onNetworkChange");
    }

    public void initLocationEventHandling() {
        //Location update events
        lm.removeUpdates(this);
        if (gpsListener != null) {
            lm.removeGpsStatusListener(gpsListener);
            gpsListener=null;
        }
        if (useGPS) {
            Log.i(Logger.TAG,"using GPS+network for location");
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 120, 60, this);
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER,100,60, this);
            gpsListener=new GpsStatus.Listener() {
                @Override
                public void onGpsStatusChanged(int event) {
                    Log.d(Logger.TAG, "onGpsStatusChanged: " + event);
                    synchronized (info) {
                        info.updateGPS(event, lm);
                    }
                    updateScreen();
                }
            };
            lm.addGpsStatusListener(gpsListener);
            Toast.makeText(getBaseContext(),"Using GPS now", Toast.LENGTH_LONG).show();
        } else {
            //don't use GPS anymore
            lm.removeUpdates(this);
            Log.i(Logger.TAG,"using network for location");
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 120, 60, this);
        }

    }

    public void initNetworkEventHandling() {
        Log.d(Logger.TAG,"initNetworkEventHandling");
        //Network changes notifications
        if (networkStateReciever==null) {
            //We listen to network state changes so we can keep track of our ip and server
            IntentFilter filter = new IntentFilter();
            filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
            filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
            networkStateReciever=new NetworkStateReceiver(this);
            registerReceiver(networkStateReciever, filter);
        } else {
            Log.d(Logger.TAG,"NetworkStateReciever still active ?");
        }
    }

    public void initEventHandling() {
        Log.d(Logger.TAG,"initEventHandling");
        initLocationEventHandling();
        initNetworkEventHandling();
    }

    public void removeEventHandling() {
        Log.d(Logger.TAG,"removeEventHandling");
        //remove network notifications updates
        if (networkStateReciever != null) {
            try {
            unregisterReceiver(networkStateReciever);
            } catch(RuntimeException ignore) {
                Log.d(Logger.TAG,"oeps, should not happen",ignore);
            } finally {
                networkStateReciever=null;
            }
        }
        try {
            //remove location updates
            lm.removeUpdates(this);
        } catch (RuntimeException ignore) {
            Log.d(Logger.TAG,"oeps, should not happen",ignore);
        }
    }


    @Override
    public void onToggleGPS(Boolean state) {
      //when state changes
      Log.i(Logger.TAG, "onToggleGPS " + state);
      useGPS=state;
      if (useGPS) {
          synchronized (info) {
              info.clearGPS();
          }
      }
      //refresh eventHandling for location only
      initLocationEventHandling();
    }

    @Override
    public void onToggleTrack(Boolean state) {
        synchronized (info) {
            info.setTrack(state);
        }
        updateScreen();
    }

    public void startServer() {
        //startup HTTP server
        if (server == null) {
            server=new GPSHTTPServer(info);
            try {
                server.start();
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

            //Track switch listener
            Switch useTrackButton = (Switch) getView().findViewById(R.id.trackSwitch);
            useTrackButton.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {

                @Override
                public void onCheckedChanged(CompoundButton buttonView,boolean isChecked) {
                    ((GpsServerActivity)getActivity()).onToggleTrack(isChecked);
                }
            });

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
