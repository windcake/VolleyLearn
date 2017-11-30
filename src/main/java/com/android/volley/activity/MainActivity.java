package com.android.volley.activity;

import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;

import com.android.volley.R;
import com.android.volley.RequestQueue;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        RequestQueue requestQueue = new RequestQueue(con)


    }
}
