package com.hoho.android.usbserial.examples;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.method.ScrollingMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;
import android.graphics.drawable.ColorDrawable;
import android.graphics.Color;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
//import android.widget.EditText;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.media.ToneGenerator;
import android.media.AudioManager;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;


public class TerminalFragment extends Fragment implements SerialInputOutputManager.Listener {

    private enum UsbPermission { Unknown, Requested, Granted, Denied;}
    private static final String INTENT_ACTION_GRANT_USB = BuildConfig.APPLICATION_ID + ".GRANT_USB";
    private static final int WRITE_WAIT_MILLIS = 2000;
    private static final int READ_WAIT_MILLIS = 2000;

    private int deviceId, portNum, baudRate;
    private boolean withIoManager;

    private final BroadcastReceiver broadcastReceiver;
    private final Handler mainLooper;
    private TextView receiveText;
    private ControlLines controlLines;

    private SerialInputOutputManager usbIoManager;
    private UsbSerialPort usbSerialPort;
    private UsbPermission usbPermission = UsbPermission.Unknown;
    private boolean connected = false;
    private TextView versionText;
    private RingBuffer ringBuffer;
    private int receiveCount = 0; // 수신 카운트 변수
    private boolean isReceivingPacket = false; // 패킷 수신 여부를 확인하는 플래그
    private List<Byte> packetBuffer = new ArrayList<>(); // 패킷 데이터를 임시로 저장할 리스트
    private Button modeButton;
    // Global variables at the top of TerminalFragment.java
    private String[] modes = {"계량기조회", "계량기응답", "TCP"};
    private String selectedMode = modes[1]; // Initialize with the default mode (계량기조회)
    private PopupWindow popupWindow;
    // 리튬배터리 [V]
    private int LITUM37 = 37;
    // Packet structure
    private static final class PacketDefinition {
        byte[] startByte, endByte;
        int length;

        PacketDefinition(byte[] startByte, byte[] endByte, int length) {
            this.startByte = startByte;
            this.endByte = endByte;
            this.length = length;
        }
    }

    // Global packet definitions
    private static final PacketDefinition
            SERIAL_PACKET = new PacketDefinition(new byte[]{}, new byte[]{0x0d, 0x0a}, -1),// 가변길이
            METER_PACKET = new PacketDefinition(new byte[]{0x68}, new byte[]{0x16}, 21),// 21바이트
            TCP_PACKET = new PacketDefinition(new byte[]{}, new byte[]{}, 0); // 길이 미정의

    private TextView selectedModeText; // Class-level variable for selected mode text




    public TerminalFragment() {
        broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (INTENT_ACTION_GRANT_USB.equals(intent.getAction())) {
                    usbPermission = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                            ? UsbPermission.Granted : UsbPermission.Denied;
                    connect();
                }
            }
        };
        mainLooper = new Handler(Looper.getMainLooper());
    }
    /*
     * Lifecycle
     */

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        setRetainInstance(true);
        deviceId = getArguments().getInt("device");
        portNum = getArguments().getInt("port");
        baudRate = getArguments().getInt("baud");
        withIoManager = getArguments().getBoolean("withIoManager");
        // 링형 버퍼 초기화
        ringBuffer = new RingBuffer(1024); // 원하는 용량으로 설정
    }

    @Override
    public void onResume() {
        super.onResume();
        getActivity().registerReceiver(broadcastReceiver, new IntentFilter(INTENT_ACTION_GRANT_USB));

        if (usbPermission == UsbPermission.Unknown || usbPermission == UsbPermission.Granted)
            mainLooper.post(this::connect);
    }

    @Override
    public void onPause() {
        if (connected) {
            status("disconnected");
            disconnect();
        }
        getActivity().unregisterReceiver(broadcastReceiver);
        super.onPause();
    }
    /*
     * UI
     */

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_terminal, container, false);
        receiveText = view.findViewById(R.id.receive_text);
        receiveText.setTextColor(getResources().getColor(R.color.colorRecieveText));
        receiveText.setMovementMethod(ScrollingMovementMethod.getInstance());
        TextView sendText = view.findViewById(R.id.send_text);
        View sendBtn = view.findViewById(R.id.send_btn);
        sendBtn.setOnClickListener(v -> send(sendText.getText().toString()));
        View receiveBtn = view.findViewById(R.id.receive_btn);
        controlLines = new ControlLines(view, this);
        if (withIoManager) {
            receiveBtn.setVisibility(View.GONE);
        } else {
            receiveBtn.setOnClickListener(v -> read());
        }
        // 앱 버전 표시
        versionText = view.findViewById(R.id.version_text);
        versionText.setText("Version: " + BuildConfig.VERSION_NAME + " (" + BuildConfig.RELEASE_DATE + ")");

        // Initialize the selected mode TextView
        TextView selectedModeText = view.findViewById(R.id.selected_mode_text);
        selectedModeText.setText("선택된 모드: " + modes[0]); // Set default mode to "계량기조회"

        modeButton = view.findViewById(R.id.modeButton);

        // Set an OnClickListener on the Mode button
        modeButton.setOnClickListener(v -> showModeOptions(selectedModeText));


        // TextView 참조
        receiveText = view.findViewById(R.id.receive_text);

        // clearButton 참조 및 클릭 이벤트 설정
        Button clearButton = view.findViewById(R.id.clearButton);
        clearButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick( View v) {
                receiveText.setText("");
                receiveCount = 0;
            }
        });

        return view;
    }

    private void showModeOptions(TextView selectedModeText) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
        builder.setTitle("시험모드선택")
                .setItems(modes, (dialog, which) -> {
                    // Update the selected mode text dynamically
                    selectedModeText.setText("선택된 모드: " + modes[which]);

                    // Update the selectedMode variable based on the selection
                    selectedMode = modes[which];

                    // Handle the selection
                    switch (which) {
                        case 0:
                            // Handle Serial mode
                            // Use SERIAL_PACKET here as needed
                            // Update selectedMode to Serial
                            selectedMode = modes[0];
                            break;
                        case 1:
                            // Handle Meter mode
                            // Use METER_PACKET here as needed
                            // Update selectedMode to Meter
                            selectedMode = modes[1];
                            break;
                        case 2:
                            // Handle TCP mode
                            // Use TCP_PACKET here as needed
                            // Update selectedMode to TCP
                            selectedMode = modes[2];
                            break;
                    }
                });
        builder.create().show();
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.menu_terminal, menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.clear) {
            receiveText.setText("");
            return true;
        } else if (id == R.id.send_break) {
            if (!connected) {
                Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show();
            } else {
                try {
                    usbSerialPort.setBreak(true);
                    Thread.sleep(100); // should show progress bar instead of blocking UI thread
                    usbSerialPort.setBreak(false);
                    SpannableStringBuilder spn = new SpannableStringBuilder();
                    spn.append("send <break>\n");
                    spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorSendText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    receiveText.append(spn);
                } catch (UnsupportedOperationException ignored) {
                    Toast.makeText(getActivity(), "BREAK not supported", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(getActivity(), "BREAK failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }
    /*
     * Serial
     */

    @Override
    public void onNewData(byte[] data) {
        mainLooper.post(() -> {
            receive(data);
        });
    }

    @Override
    public void onRunError(Exception e) {
        mainLooper.post(() -> {
            status("connection lost: " + e.getMessage());
            disconnect();
        });
    }
    /*
     * Serial + UI
     */

    private void connect() {
        UsbDevice device = null;
        UsbManager usbManager = (UsbManager) getActivity().getSystemService(Context.USB_SERVICE);
        for (UsbDevice v : usbManager.getDeviceList().values())
            if (v.getDeviceId() == deviceId)
                device = v;
        if (device == null) {
            status("connection failed: device not found");
            return;
        }
        UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(device);
        if (driver == null) {
            driver = CustomProber.getCustomProber().probeDevice(device);
        }
        if (driver == null) {
            status("connection failed: no driver for device");
            return;
        }
        if (driver.getPorts().size() < portNum) {
            status("connection failed: not enough ports at device");
            return;
        }
        usbSerialPort = driver.getPorts().get(portNum);
        UsbDeviceConnection usbConnection = usbManager.openDevice(driver.getDevice());
        if (usbConnection == null && usbPermission == UsbPermission.Unknown && !usbManager.hasPermission(driver.getDevice())) {
            usbPermission = UsbPermission.Requested;
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_MUTABLE : 0;
            PendingIntent usbPermissionIntent = PendingIntent.getBroadcast(getActivity(), 0, new Intent(INTENT_ACTION_GRANT_USB), flags);
            usbManager.requestPermission(driver.getDevice(), usbPermissionIntent);
            return;
        }
        if (usbConnection == null) {
            if (!usbManager.hasPermission(driver.getDevice()))
                status("connection failed: permission denied");
            else
                status("connection failed: open failed");
            return;
        }

        try {
            usbSerialPort.open(usbConnection);
            try {
                usbSerialPort.setParameters(baudRate, 8, 1, UsbSerialPort.PARITY_NONE);
            } catch (UnsupportedOperationException e) {
                status("unsupported setParameters");
            }
            if (withIoManager) {
                usbIoManager = new SerialInputOutputManager(usbSerialPort, this);
                usbIoManager.start();
            }
            status("connected");
            connected = true;
//            controlLines.start();
            controlLines.stop(); // ControlLines 클래스에서 Stop 동작을 호출
        } catch (Exception e) {
            status("connection failed: " + e.getMessage());
            disconnect();
        }
    }

    private void disconnect() {
        connected = false;
        controlLines.stop();
        if (usbIoManager != null) {
            usbIoManager.setListener(null);
            usbIoManager.stop();
        }
        usbIoManager = null;
        try {
            usbSerialPort.close();
        } catch (IOException ignored) {}
        usbSerialPort = null;
    }

    private void send(String str) {
        if (!connected) {
            Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show();
            return;
        }
        if (str.length() > 0) {
            byte[] data = str.getBytes(Charset.forName("UTF-8"));
            try {
                usbSerialPort.write(data, WRITE_WAIT_MILLIS);
            } catch (IOException e) {
                Toast.makeText(getActivity(), "send failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void read() {
        if (!connected) {
            Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            byte[] buffer = new byte[1024];
            int numBytesRead = usbSerialPort.read(buffer, READ_WAIT_MILLIS);
            if (numBytesRead > 0) {
                receive(buffer);
            }
        } catch (IOException e) {
            Toast.makeText(getActivity(), "read failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void receive(byte[] data) {
        for (byte b : data) {
            // Check for packet start based on the selected mode
            switch (selectedMode) {
                case "계량기조회": // Serial mode
                    // Add your serial mode handling logic here if needed
                    break;

                case "계량기응답": // Meter mode
                    if (b == METER_PACKET.startByte[0]) {
                        if (!isReceivingPacket) {
                            isReceivingPacket = true; // Start receiving a new packet
                            packetBuffer.clear(); // Clear packet buffer for the new packet
                        }
                        packetBuffer.add(b); // Save start byte
                    } else if (isReceivingPacket) {
                        packetBuffer.add(b); // Store packet data in buffer

                        // Check for packet end
                        int size = packetBuffer.size();
                        if ((b == 0x0a && size > 1 && packetBuffer.get(size - 2) == 0x0d) ||
                                (b == METER_PACKET.endByte[0] && size >= METER_PACKET.length )) {
                            isReceivingPacket = false; // End receiving packet
                            processPacket(); // Process the packet
                        }
                    }
                    break;

                case "TCP": // TCP mode
                    // Add your TCP mode handling logic here if needed
                    break;

                default:
                    // Handle unknown mode or error
                    break;
            }
        }
    }


    // 수신한 패킷을 처리하는 함수
    private void processPacket() {
        // 패킷을 byte[]로 변환
        byte[] receivedPacket = new byte[packetBuffer.size()];
        for (int i = 0; i < packetBuffer.size(); i++) {
            receivedPacket[i] = packetBuffer.get(i);
        }

        // 수신 패킷 길이 계산
        int 수신패킷길이 = receivedPacket.length;

        // 패킷을 링형 버퍼에 추가
        ringBuffer.add(receivedPacket);

        // 링형 버퍼에서 데이터 제거 후 처리
        byte[] receivedData = ringBuffer.remove();

         // 수신패킷을 HEX 값으로 출력
        StringBuilder hexStringBuilder = new StringBuilder(); // Create a StringBuilder to store hex values
        for (byte b : receivedData) {
        hexStringBuilder.append(String.format("%02X ", b)); // Format each byte as two-digit hex
        }
        String hexOutput = hexStringBuilder.toString().trim();
        receiveText.append(hexOutput + "\n"); // Append the hex values to the receiveText





        String meter_value_hex = ""; // meter_value_hex 선언 및 초기화

// 수신된 패킷에서 meter_value_hex 추출
            meter_value_hex = String.format("%02X%02X%02X%02X",
                    receivedData[18],
                    receivedData[17],
                    receivedData[16],
                    receivedData[15]);
        // Status 변수에 receivedData[13] 값 저장
        int Status = receivedData[13] & 0xFF;  // Ensures the value is treated as an unsigned byte

        // Batt 변수에 Status의 하위 5bits 저장
        double Batt = (LITUM37 - (Status & 0b00011111)) * 0.1;


        try {
                if (meter_value_hex.matches("[0-9A-Fa-f]+")) {
                    // Remove any leading zeros and create the formatted value
                    // Assuming meter_value_hex is already a string representation of hex values like "00000217"
                    String formatted_value = meter_value_hex.replace(" ", "").replace("0x", "").replace(",", "");

                    // Ensure the formatted_value has a length of 8 by padding with leading zeros if necessary
                    while (formatted_value.length() < 8) {
                        formatted_value = "0" + formatted_value;
                    }
                    // Convert formatted_value to an integer
                    int meter_value_int = Integer.parseInt(meter_value_hex);

                    // Output the meter_value_int
                    receiveText.append("Meter Value: " + meter_value_int + "\n"); // Should output 217
                } else {
                    // Handle invalid hex format
                    receiveText.append("Invalid hex format: " + meter_value_hex + "\n");
                }

            } catch (NumberFormatException e) {
                // 변환 중 예외가 발생한 경우 예외 메시지 출력
                receiveText.append("Error parsing meter value hex: " + e.getMessage() + "\n");
            }











        // RX 버튼을 깜빡이게 시작
        controlLines.startReceiving();
        receiveCount++;

// ToneGenerator를 사용하여 비프음 추가
        ToneGenerator toneGen = new ToneGenerator(AudioManager.STREAM_MUSIC, 100);
        toneGen.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 200); // 200ms 동안 비프음 재생

    }

    public void status(String msg) {
        receiveText.append(msg + "\n");
    }

    private class ControlLines {
        private ToggleButton txButton;
        private ToggleButton rxButton;
        private ToggleButton toggleButton;
        private View startButton;
        private View stopButton;
        private Handler handler;
        private boolean isSending = false;
        private boolean isReceiving = false;
        private int blinkDelay = 500; // Blink interval in milliseconds
        private boolean rxFirstReceived = false; // Track first RX reception
        private boolean isRxToggled = false; // RX 버튼 상태를 추적


        public ControlLines(View view, TerminalFragment fragment) {
            txButton = view.findViewById(R.id.controlLineTx);
            rxButton = view.findViewById(R.id.controlLineRx);
            toggleButton = view.findViewById(R.id.toggleButton);
            startButton = view.findViewById(R.id.startButton);
            stopButton = view.findViewById(R.id.stopButton);
            handler = new Handler(Looper.getMainLooper());
            setupListeners();
        }

        private void setupListeners() {
            startButton.setOnClickListener(v -> start());
            stopButton.setOnClickListener(v -> stop());
        }

        public void startReceiving() {
            isReceiving = true;
            if (!rxFirstReceived) {
                rxButton.setBackgroundColor(getResources().getColor(android.R.color.holo_green_light)); // Change color on first reception
                rxFirstReceived = true; // Mark that we have received data
            }
            toggleRxButton();
        }

        private void toggleRxButton() {
            if (isRxToggled) {
                // 토글 상태일 때 원래 색상으로 복구
                rxButton.setBackgroundColor(Color.TRANSPARENT);
            } else {
                // 토글 상태가 아닐 때 녹색으로 변경
                rxButton.setBackgroundColor(Color.GREEN);
            }
            isRxToggled = !isRxToggled; // 상태를 토글
        }


        private static final int SEND_INTERVAL = 1000; // 전송 간격 (밀리초)

        private TextView statusLabel; // 상태 레이블

        private void sendData() {
            if (!connected) {
                Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show();
                return;
            }

            // 전송할 데이터 정의
            byte[] dataToSend = new byte[]{0x10, 0x5B, 0x01, 0x5C, 0x16, 0x0D, 0x0A}; // 5 바이트 검침 요청 데이터

            try {
                // 데이터 전송
                // String 형태로 전송하고 싶다면 주석을 해제하세요
                // String dataAsString = new String(dataToSend, StandardCharsets.UTF_8);
                // send(dataAsString); // send 메서드를 호출하여 문자열 전송

                // 데이터 전송
                send(new String(dataToSend, Charset.forName("UTF-8"))); // Convert byte[] to String

                // 데이터 전송 반복 여부 확인
                if (isSending) {
                    handler.postDelayed(this::sendData, SEND_INTERVAL); // SEND_INTERVAL 주기로 반복 전송
                }
            } catch (Exception e) {
                statusLabel.setText("데이터 전송 오류: " + e.getMessage()); // 오류 메시지 설정
                e.printStackTrace(); // 스택 트레이스 로그
            }
        }


        private void blinkTxButton() {
            if (isSending) {
                int currentColor = ((ColorDrawable) txButton.getBackground()).getColor();
                int newColor = (currentColor == getResources().getColor(android.R.color.holo_purple))
                        ? getResources().getColor(android.R.color.transparent)
                        : getResources().getColor(android.R.color.holo_purple);
                txButton.setBackgroundColor(newColor); // TX 버튼 색상 변경
                handler.postDelayed(this::blinkTxButton, blinkDelay); // 500ms 간격으로 깜빡임
            }
        }

        public void stopReceiving() {
            isReceiving = false;
            handler.removeCallbacksAndMessages(null);
        }

        private void start() {
            if (!isSending) {
                isSending = true;
                txButton.setBackgroundColor(getResources().getColor(android.R.color.holo_green_light)); // TX 버튼 색상 변경
                blinkTxButton(); // TX 버튼 깜빡임 시작
                sendData(); // 데이터 전송 시작
            }
        }

        private void stop() {
            isSending = false;
            isReceiving = false; // 수신 중지
            handler.removeCallbacksAndMessages(null);
            txButton.setBackgroundColor(getResources().getColor(android.R.color.transparent)); // TX 버튼 색상 초기화
            rxButton.setBackgroundColor(getResources().getColor(android.R.color.transparent)); // RX 버튼 색상 초기화
            stopReceiving(); // RX 수신 중지
        }

    }

    private class RingBuffer {
        private byte[] buffer;
        private int head;
        private int tail;
        private int capacity;
        private int size;

        public RingBuffer(int capacity) {
            this.capacity = capacity;
            this.buffer = new byte[capacity];
            this.head = 0;
            this.tail = 0;
            this.size = 0;
        }

        public void add(@NonNull byte[] data) {
            for (byte b : data) {
                if (size < capacity) {
                    buffer[head] = b;
                    head = (head + 1) % capacity;
                    size++;
                } else {
                    // Optionally handle overflow
                    // e.g., overwrite the oldest data or throw an exception
                }
            }
        }

        public byte[] remove() {
            if (size == 0) {
                return new byte[0]; // or throw an exception
            }

            byte[] data = new byte[size];
            for (int i = 0; i < size; i++) {
                data[i] = buffer[(tail + i) % capacity];
            }
            tail = (tail + size) % capacity;
            size = 0; // Reset size after removal
            return data;
        }

    }

}
