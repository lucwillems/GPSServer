package com.it4y.gpsserver;

import android.os.Build;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Map;

/**
 * Created by luc on 11/30/13.
 */
public class GPSHTTPServer  extends NanoHTTPD {
    private LocationInfo info;
    protected static final int serverPort=8080;
    public GPSHTTPServer() {
        super(serverPort);
        Log.i(Logger.TAG,"HTTP server started on port "+serverPort);
    }

    private Response createResponse(String s) {
        Response response=new Response(s);
        response.setMimeType("application/json");
        //response.addHeader("Connection","close");
        return response;
    }
    @Override
    public Response serve(String uri, Method method, Map<String, String> header, Map<String, String> parms, Map<String, String> files) {
        Log.i(Logger.TAG,"HTTP request "+method+" "+uri);
        if (uri.equalsIgnoreCase("/location")) {
           return createResponse(getLocationData());
        }
        else if (uri.equalsIgnoreCase("/gps")) {
            return createResponse(getGPSData());
        }
        else if (uri.equalsIgnoreCase("/cell")) {
            return createResponse(getCellData());
        }
        else if (uri.equalsIgnoreCase("/device")) {
            return createResponse(getDeviceData());
        }
        else if (uri.equalsIgnoreCase("/all")) {
            return createResponse(getAllData());
        }
        return createResponse(getError("invalid URI: "+uri));
    }

    private String getLocationData() {
      if (info != null) {
        try {
            return info.locationToJSON().toString();
        } catch (JSONException e) {
          return getError("JSONException: "+e.toString());
        }
      }
      return getError("no data");
    }

    private String getCellData() {
        if (info != null) {
            try {
                return info.cellToJSON().toString();
            } catch (JSONException e) {
                return getError("JSONException: "+e.toString());
            }
        }
        return getError("no data");
    }
    private String getGPSData() {
        if (info != null) {
            try {
                return info.GPStoJSON().toString();
            } catch (JSONException e) {
                return getError("JSONException: "+e.toString());
            }
        }
        return getError("no data");
    }
    private String getAllData() {
        if (info != null) {
            try {
                return info.toJSON().toString();
            } catch (JSONException e) {
                return getError("JSONException: "+e.toString());
            }
        }
        return getError("no data");
    }

    private String getDeviceData() {
        JSONObject json=new JSONObject();
        try {
            json.put("version","1.0");
            json.put("sdk", Build.VERSION.SDK_INT); // OS version
            json.put("release", Build.VERSION.RELEASE); // OS version
            json.put("model", Build.MODEL); // OS version
            json.put("manufacturer",Build.MANUFACTURER);
            return json.toString();
        } catch (JSONException e) {
            return getError("JSONException: "+e.toString());
        }
    }
    public String getError(String message) {
        try {
          JSONObject json=new JSONObject();
          json.put("version","1.0");
          json.put("error",message);
          return json.toString();
        } catch (JSONException e) {
            e.printStackTrace();
        }
      return "{}";
    }

    public void updateLocation(LocationInfo info) {
        try {
            Log.i(Logger.TAG,"HTTP location update");
            this.info=(LocationInfo)info.clone();
        } catch (CloneNotSupportedException e) {
            Log.e("HTTP","ooeps",e);
            //ignore
        }
    }
}
