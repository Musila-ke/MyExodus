package com.example.myexodus.blocking;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;

/**
 * Test-only builder for ShadowAccessibilityManager tests. compileSdk 37 stubs expose
 * ServiceInfo.name and AccessibilityServiceInfo.resolveInfo as read-only (getter-only), so
 * they are populated via the still-public JVM fields / reflection against the runtime class.
 */
final class TestServiceInfo {
    private TestServiceInfo() {}

    static AccessibilityServiceInfo accessibilityService(String packageName, String name) {
        ServiceInfo serviceInfo = new ServiceInfo();
        serviceInfo.packageName = packageName;
        serviceInfo.name = name;
        serviceInfo.exported = true;
        ResolveInfo resolveInfo = new ResolveInfo();
        resolveInfo.serviceInfo = serviceInfo;
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        try {
            // setResolveInfo exists at runtime but is absent from the compileSdk 37 stubs.
            AccessibilityServiceInfo.class
                .getDeclaredMethod("setResolveInfo", ResolveInfo.class)
                .invoke(info, resolveInfo);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return info;
    }
}
