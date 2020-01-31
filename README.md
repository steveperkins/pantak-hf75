# pantak-hf75
Java serial driver for [Pantak HF 75 X-ray generator](http://korins.com/m/td/tes06.htm). It may work with other machines in the Pantak HF family but has only been tested with the Pantak HF 75.

This driver is thread-safe - one `PantakDriver` instance can be accessed simultaneously by multiple threads without garbling serial port communication. The library controls access to the underlying serial port and waits for a Pantak HF 75 response before allowing a new command to be sent.

This driver provides a high-level Java interface to interact with the Pantak HF 75 and provides these functions:
* Connect to HF 75
* Check serial connection
* Disconnect and stop emitting
* Start emitting
* Get killivolts right now
* Get milliamps right now
* Check warm-up condition
* Override warm-up condition
* Check whether Pantak HF 75 is currently emitting
* Get interlock states via indices
* Get broken interlocks as comma-separated interlock names

Note that although this library provides some minimal input validations, it does NOT perform extensive checks to prevent consumers from destroying their Pantak HF 75s or any of the associated parts (e.g. the tube). It is the consumer's responsibility to ensure the machine can tolerate the settings that will be used. NO WARRANTY IS PROVIDED for this library.

## Installation
Download the latest compiled JAR from the [dist folder](dist/) and add it to your project's build path.

## Usage
### Instantiate `PantakDriver`
```
PantakDriver driver = new PantakDriver();
```

### Connect to the serial port
```
driver.connect("COM1");
```

You need to verify that you're sending the correct port. If the port doesn't exist or can't be connected, an `IllegalArgumentException` will be thrown.

### Get broken interlocks
```
driver.getInterlockErrors();
```

`getInterlockErrors()` returns a `boolean[]` with a `true` or `false` at each of 9 indices (9 total elements). Each index represents a specific interlock. If you don't already have a mapping of the interlock indices, you can use ```driver.getInterlockErrorText()``` instead, which returns a comma-separated string showing the names of each broken interlock.

### Check warm-up state
```
driver.isWarmedUp();
```

If the Pantak HF 75 hasn't been used in the past 4 hours, it needs to warm up the tube before emitting again. You can tell the Pantak HF 75 to bypass this safety check by using `driver.overrideWarmup()`. Bypassing warm-up and starting emission can damage your tube, so be careful with this.

### Turn on X-rays
```
driver.startEmitting(10, 30); // kV, mA
```

You need to verify that the specified kV and mA won't harm the Pantak HF 75. pantak-HF 75 provides minimal checks to prevent damage - specifically, it prevents the given kV and mA from exceeding the set limits for the Pantak HF 75.

### Turn off X-rays
```
driver.stopEmitting();
```

You can verify the Pantak HF 75 is no longer emitting with `driver.isEmitting()`, which returns a boolean to indicate emission state.

### Disconnect
```
driver.disconnect();
```

Disconnection turns off X-rays and disconnects from the serial port.

### Get max millivolts
```
driver.getMaxMillivolts();
```

### Get max watts
```
driver.getMaxWatts();
```

### Get killivolts right now
```
driver.getKv();
```

### Get milliamps right now
```
driver.getMa();
```

### Disable console output
```
driver.disableWriteToConsole();
```

By default pantak-HF 75 logs communications to/from the serial port and error messages to the console. You can turn this off with `disableWriteToConsole()`.

### Enable console output
```
driver.enableWriteToConsole();
```

You can re-enable console output with `enableWriteToConsole()` if you previously disabled it with `disableWriteToConsole()`.
