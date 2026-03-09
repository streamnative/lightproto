# LightProto

High-performance Protocol Buffers code generator for Java, optimized for serialization and deserialization speed.

## Features

- **Fastest Java Protobuf SerDe** — Unsafe-based serialization bypasses Netty ByteBuf boundary checks
- **100% wire-compatible** with proto2 and proto3 definitions
- **Zero-copy deserialization** using Netty `ByteBuf` (direct and heap memory)
- **Zero heap allocations** — reusable mutable objects, no Builder pattern overhead
- **Lazy string/bytes deserialization** — decoded only on access
- **Optimized string handling** — single-copy ASCII fast path via `sun.misc.Unsafe`
- **No runtime dependencies** — generated code is self-contained
- **Maven and Gradle plugins** for seamless build integration

## Usage

### Maven

Add the Maven plugin to your `pom.xml`:

```xml
<plugin>
    <groupId>io.github.merlimat.lightproto</groupId>
    <artifactId>lightproto-maven-plugin</artifactId>
    <version>0.5-SNAPSHOT</version>
    <executions>
        <execution>
            <goals>
                <goal>generate</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

Place `.proto` files in `src/main/proto/` and LightProto will generate Java classes during the
`generate-sources` phase.

### Gradle

Add the plugin to your `build.gradle`:

```groovy
plugins {
    id 'io.github.merlimat.lightproto' version '0.5-SNAPSHOT'
}
```

Or using the `buildscript` block:

```groovy
buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath 'io.github.merlimat.lightproto:lightproto-gradle-plugin:0.5-SNAPSHOT'
    }
}

apply plugin: 'io.github.merlimat.lightproto'
```

Place `.proto` files in `src/main/proto/` and LightProto will generate Java classes automatically
before compilation. Optional configuration:

```groovy
lightproto {
    classPrefix = ''           // prefix for generated class names
    singleOuterClass = false   // wrap all messages in a single outer class
    protocVersion = '4.34.0'   // protoc compiler version
    // protocPath = '/usr/local/bin/protoc'  // use a local protoc binary
}
```

### API Example

LightProto generates mutable, reusable objects instead of the Builder pattern used by Google Protobuf:

```java
// Create and populate
MessageMetadata md = new MessageMetadata();
md.setProducerName("producer-1")
  .setSequenceId(12345)
  .setPublishTime(System.currentTimeMillis());
md.addProperty().setKey("key1").setValue("value1");

// Serialize to ByteBuf
ByteBuf buf = PooledByteBufAllocator.DEFAULT.buffer();
md.writeTo(buf);

// Deserialize from ByteBuf (zero-copy, lazy strings)
MessageMetadata parsed = new MessageMetadata();
parsed.parseFrom(buf, buf.readableBytes());

// Reuse the object for the next message
md.clear();
md.setProducerName("producer-2")...
```

## Supported Features

| Feature | proto2 | proto3 |
|:---|:---:|:---:|
| Scalar fields (int32, int64, uint32, uint64, sint32, sint64, fixed32, fixed64, sfixed32, sfixed64, float, double, bool) | ✅ | ✅ |
| String fields | ✅ | ✅ |
| Bytes fields | ✅ | ✅ |
| Enum fields | ✅ | ✅ |
| Nested messages | ✅ | ✅ |
| `optional` fields (explicit presence) | ✅ | ✅ |
| `required` fields | ✅ | — |
| Implicit presence (no `has*()`, skip defaults) | — | ✅ |
| `oneof` | ✅ | ✅ |
| `repeated` fields | ✅ | ✅ |
| `repeated` packed encoding | ✅ | ✅ (default) |
| `map<K, V>` fields | ✅ | ✅ |
| Nested enum / message definitions | ✅ | ✅ |
| Default values | ✅ | — |
| Multiple `.proto` files / `import` | ✅ | ✅ |
| `service` / RPC definitions | ❌ | ❌ |
| Extensions | ❌ | — |
| `Any`, `Timestamp`, well-known types | ❌ | ❌ |
| `group` (deprecated) | ❌ | — |

## Performance

### Throughput Comparison (ops/&mu;s, higher is better)

| Benchmark | Google Protobuf | LightProto | Speedup |
|:---|---:|---:|---:|
| **AddressBook** (nested messages, strings) | | | |
| &emsp;Serialize | 6.4 | 22.6 | **3.5x** |
| &emsp;Fill + Serialize | 2.2 | 12.8 | **5.9x** |
| &emsp;Deserialize | 3.8 | 19.2 | **5.1x** |
| **Simple** (small numeric messages) | | | |
| &emsp;Serialize | 30.8 | 245.6 | **8.0x** |
| &emsp;Deserialize | 20.0 | 100.9 | **5.0x** |
| **Pulsar MessageMetadata** (strings, properties, batch fields) | | | |
| &emsp;Serialize | 2.8 | 10.9 | **3.8x** |
| &emsp;Deserialize | 4.2 | 14.1 | **3.4x** |
| **Pulsar BaseCommand+Send** (nested message, numerics) | | | |
| &emsp;Serialize | 15.8 | 26.8 | **1.7x** |
| &emsp;Deserialize | 12.8 | 40.3 | **3.2x** |

### Speedup Chart

```
                        Speedup over Google Protobuf (x times faster)
                        1x    2x    3x    4x    5x    6x    7x    8x
                        |     |     |     |     |     |     |     |
AddressBook Ser         |████████████████████                       3.5x
AddressBook Fill+Ser    |██████████████████████████████████████████  5.9x
AddressBook Deser       |█████████████████████████████████          5.1x
                        |     |     |     |     |     |     |     |
Simple Ser              |█████████████████████████████████████████████████ 8.0x
Simple Deser            |█████████████████████████████████          5.0x
                        |     |     |     |     |     |     |     |
Pulsar MD Ser           |██████████████████████                     3.8x
Pulsar MD Deser         |███████████████████                        3.4x
                        |     |     |     |     |     |     |     |
Pulsar Cmd Ser          |████████                                   1.7x
Pulsar Cmd Deser        |█████████████████                          3.2x
                        |     |     |     |     |     |     |     |
```

### Raw JMH Output

<details>
<summary>Click to expand full benchmark output</summary>

```
Benchmark                                                    Mode  Cnt    Score    Error   Units

-- AddressBook (nested messages with strings) --
ProtoBenchmark.protobufSerialize                            thrpt    3    6.422 ±  0.242  ops/us
ProtoBenchmark.protobufFillAndSerialize                     thrpt    3    2.190 ±  0.365  ops/us
ProtoBenchmark.protobufDeserialize                          thrpt    3    3.777 ±  0.253  ops/us
ProtoBenchmark.lightProtoSerialize                          thrpt    3   22.551 ±  0.355  ops/us
ProtoBenchmark.lightProtoFillAndSerialize                   thrpt    3   12.827 ±  2.816  ops/us
ProtoBenchmark.lightProtoDeserialize                        thrpt    3   19.166 ±  1.022  ops/us

-- Simple (small numeric messages) --
SimpleBenchmark.protobufSerialize                           thrpt    3   30.768 ± 14.107  ops/us
SimpleBenchmark.protobufDeserialize                         thrpt    3   19.988 ±  0.499  ops/us
SimpleBenchmark.lightProtoSerialize                         thrpt    3  245.575 ± 87.004  ops/us
SimpleBenchmark.lightProtoDeserialize                       thrpt    3  100.857 ±  2.221  ops/us
SimpleBenchmark.lightProtoDeserializeReadString             thrpt    3   54.111 ± 14.056  ops/us

-- Pulsar MessageMetadata (strings, repeated properties, batch fields) --
PulsarApiBenchmark.protobufSerializeMessageMetadata         thrpt    3    2.838 ±  0.580  ops/us
PulsarApiBenchmark.protobufDeserializeMessageMetadata       thrpt    3    4.176 ±  0.241  ops/us
PulsarApiBenchmark.lightProtoSerializeMessageMetadata       thrpt    3   10.852 ±  0.677  ops/us
PulsarApiBenchmark.lightProtoDeserializeMessageMetadata     thrpt    3   14.141 ±  8.862  ops/us

-- Pulsar BaseCommand + CommandSend (nested message, mostly numerics) --
PulsarApiBenchmark.protobufSerializeBaseCommand             thrpt    3   15.818 ±  4.591  ops/us
PulsarApiBenchmark.protobufDeserializeBaseCommand           thrpt    3   12.771 ±  3.192  ops/us
PulsarApiBenchmark.lightProtoSerializeBaseCommand           thrpt    3   26.814 ±  1.107  ops/us
PulsarApiBenchmark.lightProtoDeserializeBaseCommand         thrpt    3   40.296 ±  2.816  ops/us
```

</details>

### Running Benchmarks

```bash
mvn -B install
java -jar benchmark/target/benchmarks.jar
```

## License

Licensed under the Apache License, Version 2.0.
