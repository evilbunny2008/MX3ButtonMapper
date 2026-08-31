// aidl file: app/src/main/aidl/com/odiousapps/mx3buttonmapper/IKeyInjectorService.aidl
package com.odiousapps.mx3buttonmapper;

interface IKeyInjectorService {
    void injectKeyEvent(int keyCode);
    void enableAccessibilityService(String flattenedComponentName);
    void destroy();
}
