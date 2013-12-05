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
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.google.gson.reflect.TypeToken;

/**
 * Created by luc on 11/30/13.
 */
public class LocationInfo implements Cloneable {

    private Location lastLocation;
    protected static final int MAX_RECORDS=1000;
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

    private ArrayList<Location> track=new ArrayList<Location>();
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
                    if (location.distanceTo(prevLocation) > max &&
                        location.getTime()-prevLocation.getTime() > 60) {
                        //track if we are moving...
                        track.add(new Location(lastLocation));
                        while (track.size()> MAX_RECORDS) {
                            //remove HEAD from queue
                            track.remove(0);
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
        kmlbuilder.append("<extrude>1</extrude>");
        kmlbuilder.append("<altitudeMode>relativeToGround</altitudeMode>");
        kmlbuilder.append("<coordinates>");
        for(Location l: track) {
                kmlbuilder.append(l.getLongitude())
                          .append(",")
                          .append(l.getLatitude())
                          .append(",")
                          .append(l.getSpeed())
                          .append("\n");
        }
        kmlbuilder.append("</coordinates>");
        kmlbuilder.append("</LineString>");
        kmlbuilder.append("<Style><LineStyle><color>#ff0000ff</color><width>2</width></LineStyle></Style>");
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
        GsonBuilder gsonBuilder = new GsonBuilder();
        gsonBuilder.registerTypeAdapter(Location.class, new LocationSerializer());
        gsonBuilder.registerTypeAdapter(Location.class, new LocationDeserializer());
        Gson gson = gsonBuilder.create();
        Type listType = TypeToken.get(track.getClass()).getType();
        return gson.toJson(track);
    }

    public void trackFromJSON(String JSON) {
        try {
            GsonBuilder gsonBuilder = new GsonBuilder();
            gsonBuilder.registerTypeAdapter(Location.class, new LocationSerializer());
            gsonBuilder.registerTypeAdapter(Location.class, new LocationDeserializer());
            Gson gson = gsonBuilder.create();
            Type collectionType = new TypeToken<ArrayList<Location>>(){}.getType();
            ArrayList<Location> x = gson.fromJson(JSON, collectionType);
            for (Object l : x) {
               if (l instanceof Location) {
                    track.add(new Location((Location)l));
               }
            }
        } catch (RuntimeException e) {
            Log.e(Logger.TAG,"ERROR",e);
        }
    }

    class LocationSerializer implements JsonSerializer<Location>
    {
        public JsonElement serialize(Location t, Type type,JsonSerializationContext jsc) {
            JsonObject jo = new JsonObject();
            jo.addProperty("mProvider", t.getProvider());
            jo.addProperty("mLongitude",t.getLongitude());
            jo.addProperty("mLatitude",t.getLatitude());
            jo.addProperty("mAccuracy", t.getAccuracy());
            jo.addProperty("mSpeed",t.getSpeed());
            jo.addProperty("mBearing",t.getBearing());
            jo.addProperty("mAltitude",t.getAltitude());
            jo.addProperty("mTime",t.getTime());
            return jo;
        }

    }

    class LocationDeserializer implements JsonDeserializer<Location> {
        public Location deserialize(JsonElement je, Type type, JsonDeserializationContext jdc)  throws JsonParseException
        {
            JsonObject jo = je.getAsJsonObject();
            Location l = new Location(jo.getAsJsonPrimitive("mProvider").getAsString());
            l.setLongitude(jo.getAsJsonPrimitive("mLongitude").getAsDouble());
            l.setLatitude(jo.getAsJsonPrimitive("mLatitude").getAsDouble());
            l.setAccuracy(jo.getAsJsonPrimitive("mAccuracy").getAsFloat());
            l.setSpeed(jo.getAsJsonPrimitive("mSpeed").getAsFloat());
            l.setBearing(jo.getAsJsonPrimitive("mBearing").getAsFloat());
            l.setAltitude(jo.getAsJsonPrimitive("mAltitude").getAsFloat());
            l.setTime(jo.getAsJsonPrimitive("mTime").getAsLong());
            //l.setElapsedRealtimeNanos(jo.getAsJsonObject("mElapsedRealtimeNanos").getAsLong());
            l.setExtras(null);
            return l;
        }
    }
}
