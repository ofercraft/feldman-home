package com.feldman.ha.widgets.generated

enum class FactoryWidgetProviderCatalog(val widgetKey: String, val receiverClassName: String) {
    BUTTON("button", "com.feldman.ha.widgets.generated.ButtonWidgetReceiver"),
    CLOCK("clock", "com.feldman.ha.widgets.generated.ClockWidgetReceiver"),
    FAN("fan", "com.feldman.ha.widgets.generated.FanWidgetReceiver"),
    MEDIA_PLAYER("media_player", "com.feldman.ha.widgets.generated.Media_playerWidgetReceiver"),
    CLIMATE("climate", "com.feldman.ha.widgets.generated.ClimateWidgetReceiver"),
    LIGHT("light", "com.feldman.ha.widgets.generated.LightWidgetReceiver"),
    LOCK("lock", "com.feldman.ha.widgets.generated.LockWidgetReceiver"),
    ALARM("alarm", "com.feldman.ha.widgets.generated.AlarmWidgetReceiver"),
    COVER("cover", "com.feldman.ha.widgets.generated.CoverWidgetReceiver"),
    SENSOR("sensor", "com.feldman.ha.widgets.generated.SensorWidgetReceiver"),
    BINARY_SENSOR("binary_sensor", "com.feldman.ha.widgets.generated.Binary_sensorWidgetReceiver"),
    SWITCH("switch", "com.feldman.ha.widgets.generated.SwitchWidgetReceiver"),
    VACUUM("vacuum", "com.feldman.ha.widgets.generated.VacuumWidgetReceiver")
}
