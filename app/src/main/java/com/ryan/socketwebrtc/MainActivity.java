package com.ryan.socketwebrtc;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import pub.devrel.easypermissions.EasyPermissions;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        String[] perms = {
                Manifest.permission.CAMERA,
                Manifest.permission.INTERNET,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
        };

        if (!EasyPermissions.hasPermissions(this, perms)) {
            EasyPermissions.requestPermissions(this, "申请权限", 0, perms);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, this);
    }


    public void onBtnServer(View view) {
        Intent intent = new Intent(this, CallActivity.class);
        intent.putExtra("server", true);
        startActivity(intent);
        finish();
    }

    public void onBtnClient(View view) {
        Intent intent = new Intent(this, CallActivity.class);
        intent.putExtra("server", false);
        startActivity(intent);
        finish();
    }
}
