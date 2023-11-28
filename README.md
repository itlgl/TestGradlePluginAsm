# TestGradlePluginAsm
使用Gradle Plugin和ASM实现调用方法的替换，将调用LiLei.hello()的地方替换为LiGui.hello()

## plugin实现的3种方式
#### 1. 脚本文件的方式
新建一个gradle文件，写groovy代码实现plugin，例子如下：
```groovy
apply plugin: MyCustomGradlePlugin

class MyCustomGradlePlugin implements Plugin<Project> {
    ...
}
```

#### 2. buildSrc的方式
项目内新建buildSrc文件夹，此文件夹下的内容会被默认编译，不需要在setting.gradle中用include引入

#### 3. 独立module的方式
demo使用的这种方式，更灵活，可以本地调试，也可以上传到maven仓供其他项目使用

## gradle plugin实现
### 写在前面
demo使用的gradle版本为`4.1.3`，plugin内使用了当前已经废弃的Transform API。  
AGP8(Android Gradle Plugin 8.0)发布后，Transform的api被废弃，有新的简易方式转换字节码，不在demo的范围内，示例参见[github示例代码](https://github.com/android/gradle-recipes/tree/agp-7.2/BuildSrc/testAsmTransformApi)。  
有尝试用gradle 8.0写过plugin，最后卡在调试阶段，因为classpath已经不用了，不知道怎么在app上依赖plugin的module，写到一半放弃了。

### 初始化插件目录结构
在项目根目录下新建文件夹`replace-plugin`，初始化目录结构如下:
```text
replace-plugin
├── build.gradle
└── src
    └── main
        └── java
            └── com
                └── example
                    └── ReplacePlugin.kt
```

**模块的build.gradle**
```groovy
apply plugin: 'org.jetbrains.kotlin.jvm'
apply plugin: 'java-gradle-plugin'

gradlePlugin {
    plugins {
        modularPlugin {
            id = 'com.example.replace'
            implementationClass = 'com.example.ReplacePlugin'
        }
    }
}

dependencies {
    // gradle的编译依赖，内部依赖了asm 7.0版本
    implementation ('com.android.tools.build:gradle:4.1.3')
    compileOnly gradleApi()
    compileOnly localGroovy()
}
```

插件`org.jetbrains.kotlin.jvm`会引入kotlin的编译环境。

`com.android.tools.build:gradle:4.1.3`为gradle编译时的依赖，版本号需要和项目内的gradle版本号一致。它本身依赖了asm 7.0的版本，且不能被更改。写demo的时候尝试依赖了9.0版本的asm，可以使用字段`Opcodes.ASM9`，结果运行就报了`IllegalArgumentException`错误。

`gradlePlugin`是一个插件标签，用于声明plugin的id和全类名，最终会在`META-INF/gradle-plugins`目录下生成插件的描述properties文件，文件名为`com.example.replace.properties`，内容如下：
```
implementation-class=com.example.ReplacePlugin
```

### 编写plugin
plugin代码非常简单，主要逻辑在两个Transform内，DemoTransform是一个示例，ReplaceTransform里有替换调用方法的实现逻辑。
```java
class ReplacePlugin: Plugin<Project> {

    override fun apply(project: Project) {
        println("ReplacePlugin ---- apply")
        val appExtension = project.extensions.getByType(AppExtension::class.java)
        appExtension.registerTransform(DemoTransform())
        appExtension.registerTransform(ReplaceTransform())
    }
}
```

### Transfrom实现过程简介
通过DemoTransform可以很明显的看出，transform的工作过程，拿到输入input，修改后将文件放到产出dest即可，多个transform可以通过链式的调用串起来。  
如果`isIncremental()`方法返回true，就代表支持增量编译，项目内的代码通过`directoryInput.changedFiles()`可以拿到所有变化的文件，jar包可以通过`jarInput.status()`拿到jar包状态，只处理变化文件即可。


### 调试plugin
插件开发完成后，在app内的`build.gradle`内配置依赖即可调试此插件。
```groovy
apply plugin: 'com.example.replace'

buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath project(':replace-plugin')
    }
}
```

运行时，会发现报错，replace-plugin找不到，因为replace-plugin还没有编译。可是编译replace-plugin又需要先sync，卡住了。所以demo内新增了一个变量ENABLED，先置为false把replace-plugin编译过，再把变量置为true，运行app。

因为是本地调试的插件，没有版本变化，plugin内代码修改了，需要先build一下plugin的module，再build app的module，有点麻烦，不过还在可接受范围。

build app有时会发现插件没有执行，是因为app内的代码没变化，且plugin的dest产物已经生成，gradle判定无需再执行。此时只要删除plugin的dest目录`build/intermediates/transforms`，重新build一下即可。

注：在AGP8里已经没有classpath配置项了，我也没找到能在项目内配置依赖的方式。

## demo内ASM的使用记录

### DemoTransform打开后生成的apk没有dex
代码是参考一个博客抄来的，看着结构很简单，最后用`file.copyTo(dest)`就结束了，看不出什么毛病。  
最后参照firebase的插件代码，才找到原因，copyTo方法是不会创建文件夹的，dest目录不存在时，直接返回，dest根本没有复制过去，所有最后打包的apk才会没有dex文件。

### 插件运行报错IllegalArgumentException
报错代码：
```kotlin
val methodReplaceVisitor = MethodReplaceVisitor(
    Opcodes.ASM9,
    cw,
    "com/example/testgradlepluginasm/LiLei",
    "hello",
    "()Ljava/lang/String;",
    "com/example/testgradlepluginasm/LiGui",
    "hello",
    "()Ljava/lang/String;",
    Opcodes.INVOKESTATIC
)
```
看着啥问题没有，运行就报错IllegalArgumentException，也没有message，进ClassVisitor看构造方法判断也有Opcodes.ASM9这个变量，找了半天也没找到原因。  
后来才发现，是依赖的问题，编译用了ASM9.0，运行用了ASM7.0，版本不对应。  
因为项目用的gradle版本是4.1.3，它依赖了ASM7.0，在插件内声明ASM9.0的依赖不起作用。  
最终将ASM9的依赖注释掉，Opcodes.ASM9也用回Opcodes.ASM7，才正常跑起来。

### 无法替换kotlin内的static方法
开始是按照博客上的代码敲过来的，发现怎么也替换不掉，最后打log发现owner对不上，最后用javap指令把MainActivity的指令打出来，才发现kotlin的static方法调用和java不太一样，按照指令修改后，终于能把方法替换掉了。  
替换完发现app运行不起来，MainActivity类加载时报校验错误，对比发现kotlin的static调用有两条指令，只替换一条不行。为了简便，用java代码重写了static方法，重新跑plugin终于成功。

ASM的代码替换和修改最终要落到javap反编译的代码上，必须一一对应才行，比如指令是
`37: invokevirtual #71                 // Method com/example/testgradlepluginasm/LiLeiKt$Companion.hello:()Ljava/lang/String;`
那demo内的MethodReplaceVisitor就要这么写：
```kotlin
val methodReplaceVisitor = MethodReplaceVisitor(
    Opcodes.ASM7,
    cw,
    "com/example/testgradlepluginasm/LiLeiKt$Companion", // owner
    "hello", // name 方法名称
    "()Ljava/lang/String;", // descriptor 方法签名
    "com/example/testgradlepluginasm/LiGuiKt$Companion", // 替换后的owner
    "hello", // 替换后的方法名称
    "()Ljava/lang/String;", // 替换后的方法签名
    Opcodes.INVOKEVIRTUAL // 替换后调用方法的指令，对应invokevirtual
)
```

### 为什么要用java代码写替换的hello方法
调用java的static方法`LiLei.hello()`对应生成了一条指令
```
30: invokestatic  #62                 // Method com/example/testgradlepluginasm/LiLei.hello:()Ljava/lang/String;
```

而使用kotlin，static方法一般要实现在companion object伴生对象里，调用方法`LiLeiKt.hello()`的地方生成了两条指令
```
34: getstatic     #68                 // Field com/example/testgradlepluginasm/LiLeiKt.Companion:Lcom/example/testgradlepluginasm/LiLeiKt$Companion;
37: invokevirtual #71                 // Method com/example/testgradlepluginasm/LiLeiKt$Companion.hello:()Ljava/lang/String;
```

如果替换kotlin实现的static方法调用，就要替换掉对应的两条指令。demo不想写那么复杂，改用替换java类写static的方法，原理是一样的。  
指令的详细结果见:[javap结果.txt](./app/src/main/java/com/example/testgradlepluginasm/javap结果.txt)

## 参考

 - [Gradle 构建工具 #2 手把手带你自定义 Gradle 插件](https://juejin.cn/post/7098383560746696718)
 - [Java ASM系列：（029）修改已有的方法（修改－替换方法调用）](https://blog.51cto.com/lsieun/2961754)