package com.it4y.gpsserver;

import android.location.GpsSatellite;
import android.location.GpsStatus;
import android.location.Location;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.telephony.TelephonyManager;
import android.telephony.gsm.GsmCellLocation;
import android.text.format.Formatter;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;

/**
 * Created by luc on 11/30/13.
 */
public class LocationInfo implements Cloneable {


    private String provider="unknown";
    private double lat = 0;
    private double lon = 0;
    private float  speed=0;
    private float  heading=0;
    private float  accuracy=0;
    private long   locationTime=0;
    private String mccmnc;
    private String cellOperator;
    private int    lac;
    private int    cid;
    private String IP;
    private boolean wifiState=false;
    private boolean wifiChanged=true;
    private int GPSStatus= LocationProvider.OUT_OF_SERVICE;

    private int GPSLockedSatellites=0;
    private int GPSTotalSatellites=0;
    private long GPSFirstFixtime=0;
    private boolean GPSFixed=false;

    public LocationInfo() {}

    public void updateLocation(Location location) {
        if (location != null) {
            locationTime=System.currentTimeMillis()/1000;
            lat=location.getLatitude();
            lon = location.getLongitude();
            speed=location.getSpeed();
            heading=location.getBearing();
            accuracy=location.getAccuracy();
            provider=location.getProvider();
        } else {
            Log.w(Logger.TAG,"null location :-(");
        }
    }

    public void updateCellInfo(TelephonyManager tm) {
        //TODO: handle other CellLocation providers
        if (tm.getCellLocation() != null && tm.getCellLocation() instanceof GsmCellLocation) {
            lac=((GsmCellLocation)tm.getCellLocation()).getLac();
            cid=((GsmCellLocation)tm.getCellLocation()).getCid();
            mccmnc=tm.getNetworkOperator();
            cellOperator=tm.getNetworkOperatorName();
        } else {
            mccmnc="";
            cellOperator="unkown";
            lac=0;
            cid=0;
        }
    }

    public void updateWifi(ConnectivityManager cm,WifiManager wm) {
        IP= "no WIFI connection";
        boolean prevWifi=wifiState;
        wifiState=false;
        NetworkInfo[] netInfo = cm.getAllNetworkInfo();
        for (NetworkInfo ni : netInfo) {
            if (ni.getTypeName().equalsIgnoreCase("WIFI"))
                if (ni.isConnected()) {
                    IP = Formatter.formatIpAddress(wm.getConnectionInfo().getIpAddress());
                    wifiState=true;
                    break;
                }
        }
        //State changed ?
        if (wifiState != prevWifi) {
            wifiChanged=true;
        }
    }

    public void updateGPS(int event,LocationManager lm) {
       GPSStatus=event;
        if (lm != null && lm.getGpsStatus(null) != null) {
            GpsStatus gpsStatus=lm.getGpsStatus(null);
            if (event==GpsStatus.GPS_EVENT_FIRST_FIX) {
                GPSFirstFixtime=gpsStatus.getTimeToFirstFix();
                GPSFixed=true;
            }
            if (event==GpsStatus.GPS_EVENT_STARTED) {
                GPSFirstFixtime=0;
                GPSFixed=false;
            }
            if (event==GpsStatus.GPS_EVENT_STOPPED) {
                GPSFirstFixtime=0;
                GPSFixed=false;
            }
            //Count # satellites
            if (event==GpsStatus.GPS_EVENT_SATELLITE_STATUS) {
                GPSLockedSatellites=0;
                GPSTotalSatellites=0;
                Iterator sat = gpsStatus.getSatellites().iterator();
                while (sat.hasNext()) {
                    GpsSatellite satellite = (GpsSatellite) sat.next();
                    GPSTotalSatellites++;
                    if (satellite.usedInFix()) {
                        GPSLockedSatellites++;
                    }
                }
            }
            Log.i(Logger.TAG, "GPS satellite " + GPSFixed + " " + GPSFirstFixtime + " " + GPSLockedSatellites + " " + GPSTotalSatellites);
        } else {
            GPSFirstFixtime=0;
            GPSTotalSatellites=0;
            GPSLockedSatellites=0;
            GPSFixed=false;
        }
    }

    public JSONObject locationToJSON() throws JSONException {
        JSONObject json=new JSONObject();
        json.put("version","1.0");
        json.put("time",locationTime);
        json.put("provider",provider);
        json.put("longitude",lon);
        json.put("latitude",lat);
        json.put("speed",speed);
        json.put("accuracy",accuracy);
        json.put("heading",heading);
        return json;
    }

    public JSONObject cellToJSON() throws JSONException {
        JSONObject json=new JSONObject();
        json.put("version","1.0");
        json.put("operator",cellOperator);
        json.put("mccmnc",mccmnc);
        json.put("lac",lac);
        json.put("cid",cid);
        return json;
    }

    public JSONObject GPStoJSON() throws JSONException {
        JSONObject json=new JSONObject();
        json.put("version","1.0");
        json.put("locked",GPSFixed);
        json.put("locktime",GPSFirstFixtime);
        json.put("satellites",GPSTotalSatellites);
        json.put("locked_satellites",GPSLockedSatellites);
        return json;
    }

    public JSONObject toJSON() throws JSONException {
        JSONObject json=new JSONObject();
        json.put("location",locationToJSON());
        json.put("cell",cellToJSON());
        json.put("gps",GPStoJSON());
        return json;
    }


    public String getProvider() {
        return provider;
    }

    public double getLat() {
        return lat;
    }

    public double getLon() {
        return lon;
    }

    public float getSpeed() {
        return speed;
    }

    public float getHeading() {
        return heading;
    }

    public float getAccuracy() {
        return accuracy;
    }

    public long getLocationTime() {
        return locationTime;
    }

    public String getMccmnc() {
        return mccmnc;
    }

    public String getCellOperator() {
        return cellOperator;
    }

    public int getLac() {
        return lac;
    }

    public int getCid() {
        return cid;
    }

    public String getIP() {
        return IP;
    }

    public boolean getWifiState() { return wifiState;}
    public boolean getGPSLocked() { return GPSFixed; }
    public int getGPSLockedSatellites() { return GPSLockedSatellites;}
    public int getGPSTotalSatellites() { return GPSTotalSatellites;}
    public long getGPSFirstFixtime() { return GPSFirstFixtime;}

    public Location getLocation() {
        Location l=new Location("info");
        l.setAccuracy(accuracy);
        l.setBearing(heading);
        l.setLatitude(lat);
        l.setLongitude(lon);
        l.setSpeed(speed);
        l.setTime(locationTime);
        return l;
    }

    public boolean isWifiChanged() { boolean tmp=wifiChanged;wifiChanged=false;return tmp;}

    protected Object clone() throws CloneNotSupportedException {
        return super.clone();
    }
}
