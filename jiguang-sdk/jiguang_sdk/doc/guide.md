# JIGUANG SDK 集成指南

## jiguang_sdk.zip 集成压缩包内容

+ jiguang
    + JIGUANG SDK 组合包。
    
+ jiguang-demo
    + JIGUANG SDK 组合包集成 demo。
    
+ doc
    + 文档说明。
   

## 集成步骤

### 导入 JIGUANG SDK

通过 AS 将 SDK 作为 module 导入项目

导入步骤：AndroidStudio -> File -> New -> Import Module -> 选择 jiguang 导入


### 配置 JIGUANG SDK

settings.gradle 配置添加：

```java
include  ':jiguang'
```

在 module 的 gradle 中 添加 SDK 依赖，以 jpush 为例

```
android {
    ......
    defaultConfig {
        applicationId "com.xxx.xxx" //JPush 上注册的包名.
        ......

        manifestPlaceholders = [
            JPUSH_PKGNAME : applicationId,
            JPUSH_APPKEY : "你的 Appkey ", //JPush 上注册的包名对应的 Appkey.
            JPUSH_CHANNEL : "developer-default", //暂时填写默认值即可.
        ]
        ......
    }
    ......
}

dependencies {
    ......

    implementation project(':jiguang')
    ......
}
```

## demo 集成步骤

在 Android Studio 中，新建一个项目。 然后按照下述步骤导入 demo

### 导入 jiguang-demo

通过 AS 将 jiguang-demo 作为 module 导入项目

导入步骤：AndroidStudio -> File -> New -> Import Module -> 选择 jiguang-demo 导入

**注：导入 jiguang-demo 会同时导入 jiguang，如果没有导入 jiguang ，请按上述相同步骤导入 jiguang**

### 配置 jiguang-demo

settings.gradle 配置添加：

```java
include  ':jiguang-demo',":jiguang"
```

### 编译 jiguang-demo 运行