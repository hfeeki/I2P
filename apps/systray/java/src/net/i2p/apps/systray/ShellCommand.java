/*
 * I2P - An anonymous, secure, and fully-distributed communication network.
 * 
 * ShellCommand.java
 * 2004 The I2P Project
 * This code is public domain.
 */

package net.i2p.apps.systray;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;

/**
 * Passes a command to the OS shell for execution and manages the output.
 * <p>
 * This class must be kept <code>gcj</code>-compatible.
 * 
 * @author hypercubus
 */
public class ShellCommand {

    private static final boolean CONSUME_OUTPUT    = true;
    private static final boolean NO_CONSUME_OUTPUT = false;

    private static final boolean WAIT_FOR_EXIT_STATUS    = true;
    private static final boolean NO_WAIT_FOR_EXIT_STATUS = false;

    private boolean       _commandSuccessful;
    private CommandThread _commandThread;
    private InputStream   _errorStream;
    private InputStream   _inputStream;
    private OutputStream  _outputStream;
    private Process       _process;

    /**
     * Executes a shell command in its own thread.
     * 
     * @author hypercubus
     */
    private class CommandThread extends Thread {

        Object  caller;
        boolean consumeOutput;
        String  shellCommand;

        CommandThread(Object caller, String shellCommand, boolean consumeOutput) {
            super("CommandThread");
            this.caller = caller;
            this.shellCommand = shellCommand;
            this.consumeOutput = consumeOutput;
        }

        public void run() {
            _commandSuccessful = execute(shellCommand, consumeOutput, WAIT_FOR_EXIT_STATUS);
            synchronized(caller) {
                caller.notify();  // In case the caller is still in the wait() state.
            }
            return;
        }
    }

    /**
     * Consumes stream data. Instances of this class, when given the
     * <code>STDOUT</code> and <code>STDERR</code> input streams of a
     * <code>Runtime.exec()</code> process for example, will prevent blocking
     * during a <code>Process.waitFor()</code> loop and thereby allow the
     * process to exit properly. This class makes no attempt to preserve the
     * consumed data.
     * 
     * @author hypercubus
     */
    private class StreamConsumer extends Thread {

        private BufferedReader    bufferedReader;
        private InputStreamReader inputStreamReader;

        public StreamConsumer(InputStream inputStream) {
            super("StreamConsumer");
            this.inputStreamReader = new InputStreamReader(inputStream);
            this.bufferedReader = new BufferedReader(inputStreamReader);
        }

        public void run() {

            String streamData;

            try {
                while ((streamData = bufferedReader.readLine()) != null) {
                    // Just like a Hoover.
                }
            } catch (IOException e) {
                // Don't bother.
            }
        }
    }

    /**
     * Reads data from a <code>java.io.InputStream</code> and writes it to
     * <code>STDOUT</code>.
     * 
     * @author hypercubus
     */
    private class StreamReader extends Thread {

        final int BUFFER_SIZE = 1024;

        private BufferedReader    bufferedReader;
        private InputStreamReader inputStreamReader;

        public StreamReader(InputStream inputStream) {
            super("StreamReader");
            this.inputStreamReader = new InputStreamReader(inputStream);
            this.bufferedReader = new BufferedReader(inputStreamReader);
        }

        public void run() {

            char[] buffer    = new char[BUFFER_SIZE];
            int    bytesRead;

            try {

                while (true)
                    while ((bytesRead = bufferedReader.read(buffer, 0, BUFFER_SIZE)) != -1)
                        for (int i = 0; i < bytesRead; i++)
                            System.out.print(buffer[i]);  // TODO Pipe this to the calling thread instead of STDOUT

            } catch (IOException e) {
                // Don't bother.
            }
        }
    }

    /**
     * Reads data from <code>STDIN</code> and writes it to a
     * <code>java.io.OutputStream</code>.
     * 
     * @author hypercubus
     */
    private class StreamWriter extends Thread {

        private BufferedWriter     bufferedWriter;
        private BufferedReader     in;
        private OutputStreamWriter outputStreamWriter;

        public StreamWriter(OutputStream outputStream) {
            super("StreamWriter");
            this.outputStreamWriter = new OutputStreamWriter(outputStream);
            this.bufferedWriter = new BufferedWriter(outputStreamWriter);
        }

        public void run() {

            String input;

            in = new BufferedReader(new InputStreamReader(System.in));
            try {
                while (true) {
                    input = in.readLine() + "\r\n";
                    bufferedWriter.write(input, 0, input.length());
                    bufferedWriter.flush();
                }
            } catch (Exception e) {
                try {
                    bufferedWriter.flush();
                } catch (IOException e1) {
                    // Eat it.
                }
            }
        }
    }

    /**
     * Passes a command to the shell for execution and returns immediately
     * without waiting for an exit status. All output produced by the
     * executed command will go to <code>STDOUT</code> and <code>STDERR</code>
     * as appropriate, and can be read via {@link #getOutputStream()} and
     * {@link #getErrorStream()}, respectively. Input can be passed to the
     * <code>STDIN</code> of the shell process via {@link #getInputStream()}.
     * 
     * @param  _shellCommand The command for the shell to execute.
     */
    public void execute(String shellCommand) {
        execute(shellCommand, NO_CONSUME_OUTPUT, NO_WAIT_FOR_EXIT_STATUS);
    }

    /**
     * Passes a command to the shell for execution. This method blocks until
     * all of the command's resulting shell processes have completed. All output
     * produced by the executed command will go to <code>STDOUT</code> and
     * <code>STDERR</code> as appropriate, and can be read via
     * {@link #getOutputStream()} and {@link #getErrorStream()}, respectively.
     * Input can be passed to the <code>STDIN</code> of the shell process via
     * {@link #getInputStream()}.
     * 
     * @param  _shellCommand The command for the shell to execute.
     * @return              <code>true</code> if the spawned shell process
     *                      returns an exit status of 0 (indicating success),
     *                      else <code>false</code>.
     */
    public boolean executeAndWait(String shellCommand) {

        if (execute(shellCommand, NO_CONSUME_OUTPUT, WAIT_FOR_EXIT_STATUS))
            return true;

        return false;
    }

    /**
     * Passes a command to the shell for execution. This method blocks until
     * all of the command's resulting shell processes have completed, unless a
     * specified number of seconds has elapsed first. All output produced by the
     * executed command will go to <code>STDOUT</code> and <code>STDERR</code>
     * as appropriate, and can be read via {@link #getOutputStream()} and
     * {@link #getErrorStream()}, respectively. Input can be passed to the
     * <code>STDIN</code> of the shell process via {@link #getInputStream()}.
     * 
     * @param  _shellCommand The command for the shell to execute.
     * @param  seconds      The method will return <code>true</code> if this
     *                      number of seconds elapses without the process
     *                      returning an exit status. A value of <code>0</code>
     *                      here disables waiting.
     * @return              <code>true</code> if the spawned shell process
     *                      returns an exit status of 0 (indicating success),
     *                      else <code>false</code>.
     */
    public synchronized boolean executeAndWaitTimed(String shellCommand, int seconds) {

        _commandThread = new CommandThread(Thread.currentThread(), shellCommand, NO_CONSUME_OUTPUT);
        _commandThread.start();
        try {

            if (seconds > 0) {
                wait(seconds * 1000);
                return true;
            }

        } catch (InterruptedException e) {
            // Wake up, time to die.
        }

        if (_commandSuccessful)
            return true;

        return false;
    }

    /**
     * Passes a command to the shell for execution and returns immediately
     * without waiting for an exit status. Any output produced by the executed
     * command will not be displayed.
     * 
     * @param  _shellCommand The command for the shell to execute.
     * @throws IOException
     */
    public void executeSilent(String shellCommand) throws IOException {
        Runtime.getRuntime().exec(shellCommand, null);
    }

    /**
     * Passes a command to the shell for execution. This method blocks until
     * all of the command's resulting shell processes have completed. Any output
     * produced by the executed command will not be displayed.
     * 
     * @param  _shellCommand The command for the shell to execute.
     * @return              <code>true</code> if the spawned shell process
     *                      returns an exit status of 0 (indicating success),
     *                      else <code>false</code>.
     */
    public boolean executeSilentAndWait(String shellCommand) {

        if (execute(shellCommand, CONSUME_OUTPUT, WAIT_FOR_EXIT_STATUS))
            return true;

        return false;
    }

    /**
     * Passes a command to the shell for execution. This method blocks until
     * all of the command's resulting shell processes have completed unless a
     * specified number of seconds has elapsed first. Any output produced by the
     * executed command will not be displayed.
     * 
     * @param  _shellCommand The command for the shell to execute.
     * @param  seconds      The method will return <code>true</code> if this
     *                      number of seconds elapses without the process
     *                      returning an exit status. A value of <code>0</code>
     *                      here disables waiting.
     * @return              <code>true</code> if the spawned shell process
     *                      returns an exit status of 0 (indicating success),
     *                      else <code>false</code>.
     */
    public synchronized boolean executeSilentAndWaitTimed(String shellCommand, int seconds) {

        _commandThread = new CommandThread(Thread.currentThread(), shellCommand, CONSUME_OUTPUT);
        _commandThread.start();
        try {

            if (seconds > 0) {
                wait(seconds * 1000);
                return true;
            }

        } catch (InterruptedException e) {
            // Wake up, time to die.
        }

        if (_commandSuccessful)
            return true;

        return false;
    }

    public InputStream getErrorStream() {
        return _errorStream;
    }

    public InputStream getInputStream() {
        return _inputStream;
    }

    public OutputStream getOutputStream() {
        return _outputStream;
    }

    private synchronized boolean execute(String shellCommand, boolean consumeOutput, boolean waitForExitStatus) {

        StreamConsumer processStderrConsumer;
        StreamConsumer processStdoutConsumer;

        StreamReader   processStderrReader;
        StreamWriter   processStdinWriter;
        StreamReader   processStdoutReader;

        try {
            _process = Runtime.getRuntime().exec(shellCommand, null);
            if (consumeOutput) {
                processStderrConsumer = new StreamConsumer(_process.getErrorStream());
                processStderrConsumer.start();
                processStdoutConsumer = new StreamConsumer(_process.getInputStream());
                processStdoutConsumer.start();
            } else {
                /*
                 * Will the following stream readers allow _process to return 
                 * just as if _process's streams had been consumed as above? If
                 * so, get rid of the stream consumers and just use the
                 * following for all cases.
                 */
                _errorStream = _process.getErrorStream();
                _inputStream = _process.getInputStream();
                _outputStream = _process.getOutputStream();
                processStderrReader = new StreamReader(_errorStream);
                processStderrReader.start();
                processStdinWriter = new StreamWriter(_outputStream);
                processStdinWriter.start();
                processStdoutReader = new StreamReader(_inputStream);
                processStdoutReader.start();
            }

            if (waitForExitStatus) {
                try {
                    _process.waitFor();
                } catch (Exception e) {
                    if (!consumeOutput) {
                        _errorStream.close();
                        _errorStream = null;
                        _inputStream.close();
                        _inputStream = null;
                        _outputStream.close();
                        _outputStream = null;
                    }
                    return false;
                }
                if (!consumeOutput) {
                    _errorStream.close();
                    _errorStream = null;
                    _inputStream.close();
                    _inputStream = null;
                    _outputStream.close();
                    _outputStream = null;
                }

                if (_process.exitValue() > 0)
                    return false;
            }

        } catch (Exception e) {
            return false;
        }
        return true;
    }
}
