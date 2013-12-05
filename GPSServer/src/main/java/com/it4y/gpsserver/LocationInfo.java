package com.it4y.gpsserver;

import android.content.SharedPreferences;
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

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

/**
 * Created by luc on 11/30/13.
 */
public class LocationInfo implements Cloneable {

    private Location lastLocation;
    private static final int MAX_RECORDS=100;
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
    private boolean enableTrack=false;

    private ConcurrentLinkedQueue<Location> track=new ConcurrentLinkedQueue<Location>();
    public LocationInfo() {}

    public void updateLocation(Location location) {
        if (location != null) {
            locationTime=System.currentTimeMillis()/1000;
            //track a copy of original location
            Location prevLocation=lastLocation;
            lastLocation=new Location(location);
            //track it
            if (enableTrack) {
                if (prevLocation !=null ) {
                    float max=Math.max(location.getAccuracy(),prevLocation.getAccuracy());
                    //if distance > max accuracy than we are moving...
                    if (location.distanceTo(prevLocation) > max) {
                        //track if we are moving...
                        track.add(new Location(lastLocation));
                        if (track.size()> MAX_RECORDS) {
                            //remove HEAD from queue
                            track.poll();
                        }
                    }
                } else {
                    if (track.size()==0) {
                        track.add(lastLocation);
                    }
                }
            }
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
        if (lastLocation != null) {
            json.put("version","1.0");
            json.put("time",lastLocation.getTime());
            json.put("provider",lastLocation.getProvider());
            json.put("longitude",lastLocation.getLongitude());
            json.put("latitude",lastLocation.getLatitude());
            json.put("speed",lastLocation.getSpeed());
            json.put("accuracy",lastLocation.getAccuracy());
            json.put("bearing",lastLocation.getBearing());
        } else {
            json.put("time",0);
            json.put("provider","unknown");
            json.put("longitude",0);
            json.put("latitude",0);
            json.put("speed",0);
            json.put("accuracy",0);
            json.put("bearing",0);
        }
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

    public String exportTrackKML() {
        StringBuilder kmlbuilder=new StringBuilder();
        //XML header
        kmlbuilder.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        kmlbuilder.append("<kml xmlns=\"http://www.opengis.net/kml/2.2\">");
        kmlbuilder.append("<Document>");
        kmlbuilder.append("<name>GPSServer</name>");
        kmlbuilder.append("<Folder>");
        kmlbuilder.append("<name>Track</name>");
        kmlbuilder.append("<visibility>1</visibility>");
        kmlbuilder.append("<Placemark>");
        kmlbuilder.append("<name>last</name>");
        kmlbuilder.append("<visibility>1</visibility>");
        kmlbuilder.append("<LineString>");
        kmlbuilder.append("<tessellate>1</tessellate>");
        kmlbuilder.append("<coordinates>");
        for(Location l: track) {
                kmlbuilder.append(l.getLongitude()).append(",").append(l.getLatitude()).append(",0\n");
        }
        kmlbuilder.append("</coordinates>");
        kmlbuilder.append("</LineString>");
        kmlbuilder.append("</Placemark>");
        kmlbuilder.append("</Folder>");
        kmlbuilder.append("</Document>");
        kmlbuilder.append("</kml>");
        return kmlbuilder.toString();
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
        return lastLocation;
    }

    public boolean isWifiChanged() { boolean tmp=wifiChanged;wifiChanged=false;return tmp;}

    protected Object clone() throws CloneNotSupportedException {
        return super.clone();
    }
    public int getTrackSize() {
        return track.size();
    }

    public void setTrack(boolean enable) {
        enableTrack=enable;
        if (!enable) {
            //flush all data
            track.clear();
        } else {
            if (lastLocation != null) {
                track.add(lastLocation);
            }
        }
    }

    public void clearGPS() {
        GPSFixed=false;
        GPSTotalSatellites=0;
        GPSLockedSatellites=0;
        GPSFirstFixtime=0;
    }

    public String trackToJSON() {
        Gson gson = new Gson();
        Type listType = TypeToken.get(track.getClass()).getType();
        return gson.toJson(track);
    }

    public void trackFromJSON(String JSON) {
        Gson gson = new Gson();
        ConcurrentLinkedQueue<Location> x= new ConcurrentLinkedQueue<Location>();
        Type listType = TypeToken.get(x.getClass()).getType();
        track.addAll((new Gson()).<Collection<Location>>fromJson(JSON, listType));
    }

}
