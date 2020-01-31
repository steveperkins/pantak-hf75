/**
 * Unless explicitly stated otherwise all files in this repository are licensed under the Apache Software License 2.0.
 */

package com.sperkins.pantak;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.fazecast.jSerialComm.SerialPort;
import com.fazecast.jSerialComm.SerialPortInvalidPortException;

/** 
 * Serial driver for the Pantak HF75 X-ray emitter.
 * This driver does not support all options on the
 * Pantak HF75. Specifically, focus operation is
 * excluded.
 * 
 * @author Steve Perkins
 *
 */
public class PantakDriver {
	// "Map" of interlock status indices to their names
	private static final String[] INTERLOCK_NAMES = new String[9];
	// Terminator that must end every command
	private static final String COMMAND_TERMINATOR = "\r";
	// Terminator of every serial response
	private static final int RESPONSE_TERMINATOR = 62; // ASCII '>' in decimal
	// Regex to exclude any garbage characters around serial responses
	private static final Pattern INPUT_GARBAGE_REGEX = Pattern.compile("[^a-zA-Z0-9]+");
	// Number format required when sending voltage and amps to equipment
	private static final DecimalFormat VOLTS_AMPS_FORMAT = new DecimalFormat("0000");
	private SerialPort serialPort;
	// Whether output will be logged to the console
	private Boolean writeLogs = Boolean.TRUE;
	
	public PantakDriver()
	{
		INTERLOCK_NAMES[0] = "Cooling";
		INTERLOCK_NAMES[1] = "Overselect";
		INTERLOCK_NAMES[2] = "Interlock";
		INTERLOCK_NAMES[3] = "Over kV";
		INTERLOCK_NAMES[4] = "Over mA";
		INTERLOCK_NAMES[5] = "Supply";
		INTERLOCK_NAMES[6] = "Filament";
		INTERLOCK_NAMES[7] = "kV diff";
		INTERLOCK_NAMES[8] = "mA diff";
	}
	
	/**
	 * @return whether this driver is currently connected to a Pantak HF75
	 */
	public Boolean isConnected() {
		return null != serialPort && serialPort.isOpen();
	}
	
	/**
	 * Attempts to connect to the Pantak HF75 on the port at <code>portName</code>
	 * 
	 * @param portName port to connect to
	 * @throws IllegalArgumentException when the port doesn't exist or can't be opened
	 */
	public void connect(String portName) throws IllegalArgumentException {
		// First make sure we don't leave the currently-connected port locked up
		disconnect();
		
		serialPort = SerialPort.getCommPort(portName);
		serialPort.setBaudRate(9600);
		serialPort.setParity(SerialPort.NO_PARITY);
		serialPort.setNumDataBits(8);
		serialPort.setNumStopBits(SerialPort.ONE_STOP_BIT);
		log("Opening port...");
		try {
			// Attempt to connect
			serialPort.openPort();
		} catch(SerialPortInvalidPortException e) {
			throw new IllegalArgumentException(e);
		}
		
		// Hooray it connected!
		log("Port open");
		// Tell port driver to block until at least one character is found on the input stream
		serialPort.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 0, 0);
		
		// RESET (ESC character/decimal 27)
		send(new byte[] { 0x001B });
		// Use ASCII communication mode
		send("A");
	}
	
	/**
	 * Disconnects from the Pantak HF75
	 */
	public void disconnect() {
		if(null != serialPort && serialPort.isOpen()) {
			try
			{
				// Don't leave the Pantak HF75 in an emitting state if we're not going to control it anymore!
				stopEmitting();
			}
			catch (Exception e) { }
			// Shut 'er down
			serialPort.closePort();
		}
	}
	
	/**
	 * Turns on X-ray emission using the provided settings.
	 * If the settings would create an unsafe tube condition (max mA exceeded),
	 * they are modified to max allowable values.
	 * 
	 * @param kV target killivolts
	 * @param mA target milliamps
	 * @throws IllegalArgumentException when <code>kV</code> or <code>mA</code> are less than 0
	 */
	public void startEmitting(double kV, double mA) throws IllegalArgumentException
	{
		if(kV < 0) { throw new IllegalArgumentException("kV must be 0 or more"); }
		if(mA < 0) { throw new IllegalArgumentException("mA must be 0 or more"); }
		
		// Verify we aren't going to overload the Pantak HF75 with the provided settings
		double maxMilliamps = getMaxWatts() / kV;
		if (maxMilliamps < mA)
		{
			log("Requested milliamps " + mA + " exceeds max milliamps " + maxMilliamps);
			mA = maxMilliamps;
		}

		// Voltage and current are always 4 digits
		kV *= 10;
		mA *= 10;
		// Set the new voltage
		send(Command.SET_VOLTS.toString() + VOLTS_AMPS_FORMAT.format(kV));
		// Set the new amps
		send(Command.SET_AMPS.toString() + VOLTS_AMPS_FORMAT.format(mA));
		// Turn on X-rays
		send(Command.START_EMITTING);
	}
	
	/**
	 * Turns off X-ray emission 
	 */
	public void stopEmitting()
	{
		send(Command.STOP_EMITTING);
	}
	
	/**
	 * Queries the Pantak HF75 for the current interlock error states
	 * 
	 * @return array of booleans where each value indicates whether the interlock at that index is in fault (true == fault, false == OK)
	 */
	public boolean[] getInterlockErrors()
	{
		String result = send(Command.GET_INTERLOCKS);
		boolean[] statuses = new boolean[9];
		// Interlock response should always be 9 digits
		if (result.length() == 9)
		{
			char[] chars = result.toCharArray();
			for(int x = 0; x < statuses.length; x++)
			{
				statuses[x] = chars[x] == 0;
			}
		}
		return statuses;
	}

	/**
	 * Queries the Pantak HF75 for the current interlock errors
	 * 
	 * @return comma-separated string of the interlock names currently in error state
	 */
	public String getInterlockErrorText()
	{
		boolean[] errors = getInterlockErrors();
		List<String> texts = new ArrayList<String>();

		for (int x = 0; x < errors.length; x++)
		{
			if (errors[x])
			{
				// If any interlock reports true, that interlock is broken
				texts.add(INTERLOCK_NAMES[x]);
			}
		}
		return String.join(", ", texts);
	}
	
	/**
	* Queries the Pantak HF75 for its current output killivolts.
	* Note that this is not the DESIRED killivolts, but the actual
	* output at the point the query is received. When the
	* unit is not actively emitting, a floating value is returned
	* (less than 1).
	* 
	* @return the current current output, in killivolts. If the Pantak HF75 finds a garbled transmission, -1 is returned.
	*/
	public double getKv()
	{
		String result = send(Command.GET_VOLTS);
		if (null != result && result.length() == 4)
		{
			return Integer.parseInt(result) / 10.0;
		}
		return -1;
	}
	
	/**
	* Queries the Pantak HF75 for its current output milliamps.
	* Note that this is not the DESIRED milliamps, but the actual
	* current output at the point the query is received. When the
	* unit is not actively emitting, a floating value is returned
	* (less than 1).
	* 
	* @return the current current output, in milliamps. If the Pantak HF75 finds a garbled transmission, -1 is returned.
	*/
	public double getMa()
	{
		String result = send(Command.GET_AMPS);
		if (null != result && result.length() == 4)
		{
			return Integer.parseInt(result) / 10.0;
		}
		return -1;
	}
	
	/**
	 * Queries the Pantak HF75 to find out whether it's currently emitting X-rays
	 * @return true if X-rays are actively being emitted, false otherwise
	 */
	public Boolean isEmitting()
	{
		String result = send(Command.GET_ON_OFF);
		if (null != result && result.length() == 1)
		{
			// 0 is emitting, 1 is not emitting
			return "0".equals(result);
		}
		return false;
	}
	
	/**
	 * Queries the Pantak HF75 for its warmed-up status
	 * @return true if unit is already warmed up, false if a warm-up is required before use
	 */
	public Boolean isWarmedUp()
	{
		String result = send(Command.GET_WARMED_UP);
		if (null != result && result.length() == 1)
		{
			// 0 is not warmed up, 1 is warmed up
			return "1".equals(result);
		}
		return Boolean.FALSE;
	}
			
	/**
	 * Tells the Pantak HF75 that warmup is not required.
	 * Trips a flag in the Pantak firmware that prevents it
	 * from forcing a warm-up period. 
	 */
	public void overrideWarmup()
	{
		send(Command.OVERRIDE_WARMUP);
	}
	
	/**
	 * Turns off console output from this driver
	 */
	public void disableWriteToConsole() {
		writeLogs = Boolean.FALSE;
	}
	
	/**
	 * Turns on console output from this driver
	 */
	public void enableWriteToConsole() {
		writeLogs = Boolean.TRUE;
	}
	
	/**
	 * @return maximum voltage, in millivolts, the Pantak HF75 can emit
	 */
	public Double getMaxMillivolts() {
		return 75.0;
	}
	
	/**
	 * @return maximum wattage the Pantak HF75 can emit
	 */
	public Double getMaxWatts() {
		return 450.0;
	}
	
	/**
	 * Sends the specified command to the serial port and returns the
	 * next response received.
	 * 
	 * @param cmd command to send
	 * @return the next response in the serial port's input stream
	 */
	private String send(Command cmd) {
		return send(cmd.toString());
	}
	
	/**
	 * Sends the specified command to the serial port and returns the
	 * next response received (determined by the response terminator).
	 * For most commands this is an empty string, but it sufficiently
	 * advances the stream position.
	 * 
	 * @param cmd command to send
	 * @return the next appropriately-terminated value on the port's input stream
	 */
	private String send(String cmd)
	{
		String command = cmd.toString() + COMMAND_TERMINATOR;
		log("SEND | " + command);

		return send(command.getBytes());
	}
	
	/**
	 * Sends the specified byte sequence to to the serial port.
	 * Pantak HF75 supports both hex and ASCII sequences, but
	 * must first be signaled to use one or the other.
	 * 
	 * @param cmd command to send
	 * @return the next response in the serial port's input stream
	 */
	private String send(byte[] cmd)
	{
		serialPort.writeBytes(cmd, cmd.length);
		return waitForResponse();
	}
	
	/**
	 * Blocks until a response ending with the response terminator is found
	 * 
	 * @return the sequence of characters returned from the Pantak HF75, up to the response termination character
	 * @throws IllegalArgumentException when the Pantak HF75 returns "COMMUNICATION ERROR", meaning it has no idea what's going on
	 */
	private String waitForResponse()
	{
		StringBuilder sb = new StringBuilder();
		boolean terminatorFound = false;
		while(!terminatorFound)
		{
			byte[] buffer = new byte[1];
			if(serialPort.readBytes(buffer, buffer.length) > 0)
			{
				char next = (char)buffer[0];
				if(next == RESPONSE_TERMINATOR)
				{
					terminatorFound = true;
					log("RECV | " + sb.toString());
					if(serialPort.bytesAvailable() > 0)
					{
						// There are more bytes waiting after this response but there shouldn't be, so we'll consume them
						log(serialPort.bytesAvailable() + " bytes remaining in stream");
						
						buffer = new byte[serialPort.bytesAvailable()];
						serialPort.readBytes(buffer, buffer.length);
						String error = new String(buffer);
						log(error);
						if(error.contains("COMMUNICATION ERROR"))
						{
							// Pantak HF75 got garbled communication.
							throw new IllegalArgumentException("Pantak returned generic error");
						}
					}
				}

				sb.append(next);
			}
		}
		return INPUT_GARBAGE_REGEX.matcher(sb.toString()).replaceAll("");
	}
	
	/**
	 * Logs the given <code>msg</code> to the console
	 * 
	 * @param msg the message to log
	 */
	private void log(String msg)
	{
		// Only write to the console if the consumer hasn't disabled it
		if(writeLogs)
		{
			System.out.println(msg);
		}
	}
	
	/**
	 * Commands supported by the Pantak HF75
	 * 
	 * @author Steve Perkins
	 *
	 */
	private enum Command
	{
		GET_VOLTS("v"),
		GET_AMPS("m"),
		GET_ON_OFF("s"),
		GET_WARMED_UP("w"),
		GET_INTERLOCKS("i"),
		SET_VOLTS("V"),
		SET_AMPS("M"),
		START_EMITTING("S"),
		STOP_EMITTING("E"),
		OVERRIDE_WARMUP("911");
		
		private String cmdStr;
		Command(String cmdStr) {
			this.cmdStr = cmdStr;
		}
		
		public String toString() {
			return this.cmdStr;
		}
	}
	
}
