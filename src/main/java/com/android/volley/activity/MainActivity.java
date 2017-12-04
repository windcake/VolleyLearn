package com.android.volley.activity;

import android.content.Context;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;

import com.android.volley.R;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;

public class MainActivity extends AppCompatActivity {

    private Context context = this;
    private static String TAG = "MainActivity";
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);


        RequestQueue requestQueue = Volley.newRequestQueue(context);
        String url ="https://www.baidu.com";

        StringRequest stringRequest = new StringRequest(Request.Method.GET, url, new Response.Listener<String>()
                                                        {
                                                            @Override
                                                            public void onResponse(String response)
                                                            {
                                                                Log.i(TAG, response);
                                                            }
                                                        }, new Response.ErrorListener()
        {
            @Override
            public void onErrorResponse(VolleyError error)
            {

            }
        });

        requestQueue.add(stringRequest);
    }
}
