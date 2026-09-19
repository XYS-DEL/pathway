# nfc

可复用的 Android NFC 模块：读卡、模拟发卡、配置持久化。

- 零第三方依赖（只用 `android.jar`）
- 不含任何资源文件
- 不依赖本项目其他模块，可整个目录复制到别的工程

## 引入

### 方式一：复制模块目录

把 `nfc/` 整个目录复制到目标工程根目录，然后在 `settings.gradle` 里：

```groovy
include ':nfc'
```

在 app 模块的 `build.gradle` 里：

```groovy
dependencies {
    implementation project(':nfc')
}
```

### 方式二：发布到本地 maven

在 `nfc/build.gradle` 里加上 `maven-publish` 插件并配置 `publishing` 块，然后
`gradlew :nfc:publishToMavenLocal`，其他工程即可用
`implementation 'com.acooldog:nfc:1.0.0'` 引用。

## 接入前：manifest

模块自己不声明任何权限和组件。接入方的 manifest 需要写：

```xml
<uses-permission android:name="android.permission.NFC" />
<uses-feature android:name="android.hardware.nfc" android:required="false" />
```

另外，读卡依赖 Android 的前台调度，接收标签的 Activity 建议使用默认启动模式
（本项目 `NfcToolsActivity` 即为默认），不要设成 `singleInstance`。

## 公开 API

只有 `com.acooldog.nfc` 包下的这几个类是给外部用的，`com.acooldog.nfc.internal`
是模块内部实现，不要直接依赖。

### 读卡

```java
private NfcReaderSession session;

@Override
protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    session = new NfcReaderSession(this, payload -> {
        if (payload.isEmpty()) {
            // 读到的是空标签
            return;
        }
        urlInput.setText(payload.getUrl());
        packageInput.setText(payload.getPackageName());
    });
}

@Override protected void onResume()  { super.onResume(); session.onResume(); }
@Override protected void onPause()   { session.onPause(); super.onPause(); }

@Override
protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);              // 必须，否则 getIntent() 拿到的是旧的
    session.onNewIntent(intent);
}

// 想显示硬件状态时
NfcReaderSession.Status status = session.getStatus();
// NO_HARDWARE / DISABLED / READY
```

`onResume()` 会在**首次**调用时补读一次当前 Intent，所以"手机贴着标签冷启动应用"
也能读到；之后每次回前台不会重复触发，不会覆盖用户已经改过的输入框。

### 模拟发卡

```java
NfcPayloadDispatchResult result = NfcSender.send(
        context,
        new NfcPayload(url, packageName, "manual")
);

switch (result.getStatus()) {
    case NDEF_SENT:           // 目标应用按 NDEF 处理了
    case FALLBACK_VIEW_SENT:  // 目标应用不吃 NDEF，已退化为普通 ACTION_VIEW 打开
    case FAILED:              // result.getDetail() 里有原因
}
```

行为：先构造带 URI 记录 + 应用记录（AAR）的 `ACTION_NDEF_DISCOVERED` 发给目标包名；
对方不接收时退化为指定包名的 `ACTION_VIEW`；再不行就退化为不带包名的 `ACTION_VIEW`，
交给系统挑应用。

### 配置持久化

```java
NfcConfigStore store = new NfcConfigStore(context);

// 上次用过的
store.getUrl();
store.getPackageName();
store.getSource();
store.savePayload(url, packageName, source);
NfcPayload last = store.getLastPayload();

// 本地保存的配置列表
List<SavedNfcConfig> configs = store.getSavedConfigs();
store.saveSavedConfig(new SavedNfcConfig("名字", url, packageName, source));  // 同名覆盖
store.deleteSavedConfig("名字");
```

默认落在 SharedPreferences 文件 `nfc_config_store`。需要和其他模块隔离时用
`new NfcConfigStore(context, "你自己的文件名")`——指定文件名时不会执行老数据迁移。

## 老数据迁移

本项目早期版本把 NFC 配置和跑步模拟配置混在 `simulation_prefs_store` 这一个文件里。
`NfcConfigStore` 首次构造时会从那里搬一次数据（只补新文件里还缺的键），并写
`legacy_migrated` 标记，之后不再尝试。**从别的项目引入时没有这个历史包袱，可以忽略。**

## 明确不包含

- UI。没有 Activity、layout、string。界面需要接入方自己写。
- 分享/下载。本项目里对应的后端 API 客户端在 `app` 的 `share` 包里，不属于这个模块。
