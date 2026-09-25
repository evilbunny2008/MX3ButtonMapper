// aidl file: app/src/main/aidl/com/odiousapps/mx3buttonmapper/IKeyInjectorService.aidl
package com.odiousapps.mx3buttonmapper;

interface IKeyInjectorService {
    void injectKeyEvent(int keyCode);
    void injectKeyDown(int keyCode, long downTime);
    void injectKeyUp(int keyCode, long downTime);
    void enableAccessibilityService(String flattenedComponentName);
    void destroy();
}
