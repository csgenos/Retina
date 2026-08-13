# Building Retina

Retina requires a Java 25 JDK. The checked-in Gradle 9.5.1 wrapper resolves Loom 1.17,
Minecraft 26.2, Fabric Loader/API, and Sodium from their configured repositories.

On Windows:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.4.7-hotspot'
.\gradlew.bat build
```

On other platforms:

```sh
JAVA_HOME=/path/to/jdk-25 ./gradlew build
```

The Fabric mod jar is written under `retina-fabric/build/libs/`. Sodium remains an external
runtime dependency and is not embedded in that jar.

To work only on the backend-neutral compiler and graph module:

```sh
./gradlew build -Pretina.coreOnly=true
```

To launch the development client:

```sh
./gradlew :retina-fabric:runClient
```

Set **Preferred Graphics API** to Vulkan in Minecraft's Video Settings and restart before
activating a pack. Shaders stay off by default.
