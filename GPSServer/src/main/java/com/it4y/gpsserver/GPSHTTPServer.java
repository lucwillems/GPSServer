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

    public GPSHTTPServer(LocationInfo info) {
        super(serverPort);
        Log.i(Logger.TAG,"HTTP server started on port "+serverPort);
        this.info=info;
    }

    private Response createResponse(String s,String mimeType,String filename) {
        Response response=new Response(s);
        response.setMimeType(mimeType);
        response.addHeader("Content-Disposition", "attachment;filename=" + filename );
        response.addHeader("Connection", "close");
        return response;
    }

    private Response createResponse(String s,String mimeType) {
        Response response=new Response(s);
        response.setMimeType(mimeType);
        return response;
    }

    private Response createResponse(String s) {
        return createResponse(s,"application/json");
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
        else if (uri.equalsIgnoreCase("/track")) {
            return createResponse(getTrackData(),"application/vnd.google-earth.kml+xml","track.kml");
        }
        return createResponse(getError("invalid URI: "+uri));
    }

    private String getLocationData() {
      synchronized (info) {
        try {
            return info.locationToJSON().toString();
        } catch (JSONException e) {
          return getError("JSONException: "+e.toString());
        }
      }
    }

    private String getCellData() {
        synchronized (info) {
            try {
                return info.cellToJSON().toString();
            } catch (JSONException e) {
                return getError("JSONException: "+e.toString());
            }
        }
    }
    private String getGPSData() {
        synchronized (info) {
            try {
                return info.GPStoJSON().toString();
            } catch (JSONException e) {
                return getError("JSONException: "+e.toString());
            }
        }
    }
    private String getAllData() {
        synchronized (info) {
            try {
                return info.toJSON().toString();
            } catch (JSONException e) {
                return getError("JSONException: "+e.toString());
            }
        }
    }

    private String getTrackData() {
        synchronized (info) {
                return info.exportTrackKML();
        }
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

}
