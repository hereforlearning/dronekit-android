package org.droidplanner.services.android.core.drone.autopilot.apm;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;

import com.MAVLink.Messages.MAVLinkMessage;
import com.MAVLink.ardupilotmega.msg_camera_feedback;
import com.MAVLink.ardupilotmega.msg_mag_cal_progress;
import com.MAVLink.ardupilotmega.msg_mag_cal_report;
import com.MAVLink.ardupilotmega.msg_mount_configure;
import com.MAVLink.ardupilotmega.msg_mount_status;
import com.MAVLink.ardupilotmega.msg_radio;
import com.MAVLink.common.msg_heartbeat;
import com.MAVLink.common.msg_mission_current;
import com.MAVLink.common.msg_mission_item_reached;
import com.MAVLink.common.msg_named_value_int;
import com.MAVLink.common.msg_nav_controller_output;
import com.MAVLink.common.msg_raw_imu;
import com.MAVLink.common.msg_rc_channels_raw;
import com.MAVLink.common.msg_servo_output_raw;
import com.MAVLink.common.msg_statustext;
import com.MAVLink.common.msg_vfr_hud;
import com.MAVLink.enums.MAV_MODE_FLAG;
import com.MAVLink.enums.MAV_MOUNT_MODE;
import com.MAVLink.enums.MAV_STATE;
import com.o3dr.services.android.lib.coordinate.LatLong;
import com.o3dr.services.android.lib.coordinate.LatLongAlt;
import com.o3dr.services.android.lib.drone.action.ControlActions;
import com.o3dr.services.android.lib.drone.action.ExperimentalActions;
import com.o3dr.services.android.lib.drone.action.GimbalActions;
import com.o3dr.services.android.lib.drone.action.ParameterActions;
import com.o3dr.services.android.lib.drone.action.StateActions;
import com.o3dr.services.android.lib.drone.attribute.AttributeEvent;
import com.o3dr.services.android.lib.drone.attribute.AttributeEventExtra;
import com.o3dr.services.android.lib.drone.attribute.AttributeType;
import com.o3dr.services.android.lib.drone.attribute.error.CommandExecutionError;
import com.o3dr.services.android.lib.drone.mission.action.MissionActions;
import com.o3dr.services.android.lib.drone.property.DroneAttribute;
import com.o3dr.services.android.lib.drone.property.Parameter;
import com.o3dr.services.android.lib.gcs.action.CalibrationActions;
import com.o3dr.services.android.lib.mavlink.MavlinkMessageWrapper;
import com.o3dr.services.android.lib.model.AbstractCommandListener;
import com.o3dr.services.android.lib.model.ICommandListener;
import com.o3dr.services.android.lib.model.action.Action;

import org.droidplanner.services.android.core.MAVLink.MAVLinkStreams;
import org.droidplanner.services.android.core.MAVLink.MavLinkCommands;
import org.droidplanner.services.android.core.MAVLink.MavLinkParameters;
import org.droidplanner.services.android.core.MAVLink.WaypointManager;
import org.droidplanner.services.android.core.MAVLink.command.doCmd.MavLinkDoCmds;
import org.droidplanner.services.android.core.drone.DroneInterfaces;
import org.droidplanner.services.android.core.drone.LogMessageListener;
import org.droidplanner.services.android.core.drone.Preferences;
import org.droidplanner.services.android.core.drone.autopilot.apm.variables.APMHeartBeat;
import org.droidplanner.services.android.core.drone.autopilot.generic.GenericMavLinkDrone;
import org.droidplanner.services.android.core.drone.profiles.ParameterManager;
import org.droidplanner.services.android.core.drone.profiles.VehicleProfile;
import org.droidplanner.services.android.core.drone.variables.ApmModes;
import org.droidplanner.services.android.core.drone.variables.Camera;
import org.droidplanner.services.android.core.drone.variables.GuidedPoint;
import org.droidplanner.services.android.core.drone.variables.HeartBeat;
import org.droidplanner.services.android.core.drone.variables.Magnetometer;
import org.droidplanner.services.android.core.drone.variables.MissionStats;
import org.droidplanner.services.android.core.drone.variables.RC;
import org.droidplanner.services.android.core.drone.variables.calibration.AccelCalibration;
import org.droidplanner.services.android.core.drone.variables.calibration.MagnetometerCalibrationImpl;
import org.droidplanner.services.android.core.mission.Mission;
import org.droidplanner.services.android.core.model.AutopilotWarningParser;
import org.droidplanner.services.android.utils.CommonApiUtils;

import timber.log.Timber;

/**
 * Base class for the ArduPilot autopilots
 */
public abstract class ArduPilot extends GenericMavLinkDrone {

    public static final int AUTOPILOT_COMPONENT_ID = 1;
    public static final int ARTOO_COMPONENT_ID = 0;
    public static final int TELEMETRY_RADIO_COMPONENT_ID = 68;

    private VehicleProfile profile;

    private final org.droidplanner.services.android.core.drone.variables.RC rc;
    private final Mission mission;
    private final MissionStats missionStats;
    private final GuidedPoint guidedPoint;
    private final AccelCalibration accelCalibrationSetup;
    private final WaypointManager waypointManager;
    private final Magnetometer mag;
    private final Camera footprints;
    private final ParameterManager parameterManager;

    private final Preferences preferences;

    private final LogMessageListener logListener;
    private final MagnetometerCalibrationImpl magCalibration;

    public ArduPilot(Context context, MAVLinkStreams.MAVLinkOutputStream mavClient,
                     Handler handler, Preferences pref, AutopilotWarningParser warningParser,
                     LogMessageListener logListener, DroneInterfaces.AttributeEventListener listener) {

        super(handler, mavClient, warningParser, listener);

        this.preferences = pref;
        this.logListener = logListener;

        loadVehicleProfile();

        parameterManager = new ParameterManager(this, context, handler);
        this.waypointManager = new WaypointManager(this, handler);

        rc = new RC(this);
        this.mission = new Mission(this);
        this.missionStats = new MissionStats(this);
        this.guidedPoint = new GuidedPoint(this, handler);
        this.accelCalibrationSetup = new AccelCalibration(this, handler);
        this.magCalibration = new MagnetometerCalibrationImpl(this);
        this.mag = new Magnetometer(this);
        this.footprints = new Camera(this);
    }

    @Override
    protected HeartBeat initHeartBeat(Handler handler) {
        return new APMHeartBeat(this, handler);
    }

    protected void setAltitudeGroundAndAirSpeeds(double altitude, double groundSpeed, double airSpeed, double climb) {
        if (this.altitude.getAltitude() != altitude) {
            this.altitude.setAltitude(altitude);
            notifyDroneEvent(DroneInterfaces.DroneEventsType.ALTITUDE);
        }

        if (speed.getGroundSpeed() != groundSpeed || speed.getAirSpeed() != airSpeed || speed.getVerticalSpeed() != climb) {
            speed.setGroundSpeed(groundSpeed);
            speed.setAirSpeed(airSpeed);
            speed.setVerticalSpeed(climb);

            notifyDroneEvent(DroneInterfaces.DroneEventsType.SPEED);
        }
    }

    protected void setDisttowpAndSpeedAltErrors(double disttowp, double alt_error, double aspd_error) {
        missionStats.setDistanceToWp(disttowp);

        this.altitude.setTargetAltitude(this.altitude.getAltitude() + alt_error);
        notifyDroneEvent(DroneInterfaces.DroneEventsType.ORIENTATION);
    }

    @Override
    public ParameterManager getParameterManager() {
        return parameterManager;
    }

    @Override
    public void loadVehicleProfile() {
        profile = preferences.loadVehicleProfile(getFirmwareType());
    }

    @Override
    public VehicleProfile getVehicleProfile() {
        return profile;
    }

    @Override
    public Preferences getPreferences() {
        return preferences;
    }

    @Override
    public WaypointManager getWaypointManager() {
        return waypointManager;
    }

    @Override
    public Mission getMission() {
        return mission;
    }

    @Override
    public MissionStats getMissionStats() {
        return missionStats;
    }

    @Override
    public GuidedPoint getGuidedPoint() {
        return guidedPoint;
    }

    @Override
    public AccelCalibration getCalibrationSetup() {
        return accelCalibrationSetup;
    }

    @Override
    public MagnetometerCalibrationImpl getMagnetometerCalibration() {
        return magCalibration;
    }

    @Override
    public String getFirmwareVersion() {
        return type.getFirmwareVersion();
    }

    protected void setFirmwareVersion(String message) {
        type.setFirmwareVersion(message);
    }

    @Override
    public Magnetometer getMagnetometer() {
        return mag;
    }

    public Camera getCamera() {
        return footprints;
    }

    @Override
    public void logMessage(int logLevel, String message) {
        if (logListener != null)
            logListener.onMessageLogged(logLevel, message);
    }


    @Override
    public DroneAttribute getAttribute(String attributeType) {
        if (!TextUtils.isEmpty(attributeType)) {
            switch (attributeType) {

                case AttributeType.MISSION:
                    return CommonApiUtils.getMission(this);

                case AttributeType.TYPE:
                    return CommonApiUtils.getType(this);

                case AttributeType.GUIDED_STATE:
                    return CommonApiUtils.getGuidedState(this);

                case AttributeType.MAGNETOMETER_CALIBRATION_STATUS:
                    return CommonApiUtils.getMagnetometerCalibrationStatus(this);
            }
        }

        return super.getAttribute(attributeType);
    }

    @Override
    public boolean executeAsyncAction(Action action, final ICommandListener listener) {
        String type = action.getType();
        Bundle data = action.getData();
        if (data == null) {
            data = new Bundle();
        }

        switch (type) {
            // MISSION ACTIONS
            case MissionActions.ACTION_LOAD_WAYPOINTS:
                CommonApiUtils.loadWaypoints(this);
                return true;

            case MissionActions.ACTION_SET_MISSION:
                data.setClassLoader(com.o3dr.services.android.lib.drone.mission.Mission.class.getClassLoader());
                com.o3dr.services.android.lib.drone.mission.Mission mission = data.getParcelable(MissionActions.EXTRA_MISSION);
                boolean pushToDrone = data.getBoolean(MissionActions.EXTRA_PUSH_TO_DRONE);
                CommonApiUtils.setMission(this, mission, pushToDrone);
                return true;

            case MissionActions.ACTION_START_MISSION:
                boolean forceModeChange = data.getBoolean(MissionActions.EXTRA_FORCE_MODE_CHANGE);
                boolean forceArm = data.getBoolean(MissionActions.EXTRA_FORCE_ARM);
                CommonApiUtils.startMission(this, forceModeChange, forceArm, listener);
                return true;

            //EXPERIMENTAL ACTIONS
            case ExperimentalActions.ACTION_EPM_COMMAND:
                boolean release = data.getBoolean(ExperimentalActions.EXTRA_EPM_RELEASE);
                CommonApiUtils.epmCommand(this, release, listener);
                return true;

            case ExperimentalActions.ACTION_TRIGGER_CAMERA:
                CommonApiUtils.triggerCamera(this);
                return true;

            case ExperimentalActions.ACTION_SET_ROI:
                LatLongAlt roi = data.getParcelable(ExperimentalActions.EXTRA_SET_ROI_LAT_LONG_ALT);
                if (roi != null) {
                    MavLinkDoCmds.setROI(this, roi, listener);
                }
                return true;

            case ExperimentalActions.ACTION_SEND_MAVLINK_MESSAGE:
                data.setClassLoader(MavlinkMessageWrapper.class.getClassLoader());
                MavlinkMessageWrapper messageWrapper = data.getParcelable(ExperimentalActions.EXTRA_MAVLINK_MESSAGE);
                CommonApiUtils.sendMavlinkMessage(this, messageWrapper);
                return true;

            case ExperimentalActions.ACTION_SET_RELAY:
                int relayNumber = data.getInt(ExperimentalActions.EXTRA_RELAY_NUMBER);
                boolean isOn = data.getBoolean(ExperimentalActions.EXTRA_IS_RELAY_ON);
                MavLinkDoCmds.setRelay(this, relayNumber, isOn, listener);
                return true;

            case ExperimentalActions.ACTION_SET_SERVO:
                int channel = data.getInt(ExperimentalActions.EXTRA_SERVO_CHANNEL);
                int pwm = data.getInt(ExperimentalActions.EXTRA_SERVO_PWM);
                MavLinkDoCmds.setServo(this, channel, pwm, listener);
                return true;

            //CONTROL ACTIONS
            case ControlActions.ACTION_DO_GUIDED_TAKEOFF:
                double takeoffAltitude = data.getDouble(ControlActions.EXTRA_ALTITUDE);
                CommonApiUtils.doGuidedTakeoff(this, takeoffAltitude, listener);
                return true;

            case ControlActions.ACTION_SEND_GUIDED_POINT:
                data.setClassLoader(LatLong.class.getClassLoader());
                boolean force = data.getBoolean(ControlActions.EXTRA_FORCE_GUIDED_POINT);
                LatLong guidedPoint = data.getParcelable(ControlActions.EXTRA_GUIDED_POINT);
                CommonApiUtils.sendGuidedPoint(this, guidedPoint, force, listener);
                return true;

            case ControlActions.ACTION_SET_GUIDED_ALTITUDE:
                double guidedAltitude = data.getDouble(ControlActions.EXTRA_ALTITUDE);
                CommonApiUtils.setGuidedAltitude(this, guidedAltitude);
                return true;

            case ControlActions.ACTION_SET_VELOCITY:
                //Retrieve the normalized values
                float normalizedXVel = data.getFloat(ControlActions.EXTRA_VELOCITY_X);
                float normalizedYVel = data.getFloat(ControlActions.EXTRA_VELOCITY_Y);
                float normalizedZVel = data.getFloat(ControlActions.EXTRA_VELOCITY_Z);

                //Retrieve the speed parameters.
                float defaultSpeed = 5; //m/s

                //Retrieve the horizontal speed value
                Parameter horizSpeedParam = parameterManager.getParameter("WPNAV_SPEED");
                double horizontalSpeed = horizSpeedParam == null ? defaultSpeed : horizSpeedParam.getValue() / 100;

                //Retrieve the vertical speed value.
                String vertSpeedParamName = normalizedZVel >= 0 ? "WPNAV_SPEED_UP" : "WPNAV_SPEED_DN";
                Parameter vertSpeedParam = parameterManager.getParameter(vertSpeedParamName);
                double verticalSpeed = vertSpeedParam == null ? defaultSpeed : vertSpeedParam.getValue() / 100;

                MavLinkCommands.setVelocityInLocalFrame(this, (float) (normalizedXVel * horizontalSpeed),
                        (float) (normalizedYVel * horizontalSpeed),
                        (float) (normalizedZVel * verticalSpeed),
                        listener);
                return true;

            //PARAMETER ACTIONS
            case ParameterActions.ACTION_REFRESH_PARAMETERS:
                CommonApiUtils.refreshParameters(this);
                return true;

            case ParameterActions.ACTION_WRITE_PARAMETERS:
                data.setClassLoader(com.o3dr.services.android.lib.drone.property.Parameters.class.getClassLoader());
                com.o3dr.services.android.lib.drone.property.Parameters parameters = data.getParcelable(ParameterActions.EXTRA_PARAMETERS);
                CommonApiUtils.writeParameters(this, parameters);
                return true;

            //DRONE STATE ACTIONS
            case StateActions.ACTION_ARM:
                boolean doArm = data.getBoolean(StateActions.EXTRA_ARM);
                boolean emergencyDisarm = data.getBoolean(StateActions.EXTRA_EMERGENCY_DISARM);
                CommonApiUtils.arm(this, doArm, emergencyDisarm, listener);
                return true;

            case StateActions.ACTION_SET_VEHICLE_HOME:
                LatLongAlt homeLoc = data.getParcelable(StateActions.EXTRA_VEHICLE_HOME_LOCATION);
                if (homeLoc != null) {
                    MavLinkDoCmds.setVehicleHome(this, homeLoc, new AbstractCommandListener() {
                        @Override
                        public void onSuccess() {
                            CommonApiUtils.postSuccessEvent(listener);
                            requestHomeUpdate();
                        }

                        @Override
                        public void onError(int executionError) {
                            CommonApiUtils.postErrorEvent(executionError, listener);
                            requestHomeUpdate();
                        }

                        @Override
                        public void onTimeout() {
                            CommonApiUtils.postTimeoutEvent(listener);
                            requestHomeUpdate();
                        }
                    });
                } else {
                    CommonApiUtils.postErrorEvent(CommandExecutionError.COMMAND_FAILED, listener);
                }
                return true;

            //CALIBRATION ACTIONS
            case CalibrationActions.ACTION_START_IMU_CALIBRATION:
                CommonApiUtils.startIMUCalibration(this, listener);
                return true;

            case CalibrationActions.ACTION_SEND_IMU_CALIBRATION_ACK:
                int imuAck = data.getInt(CalibrationActions.EXTRA_IMU_STEP);
                CommonApiUtils.sendIMUCalibrationAck(this, imuAck);
                return true;

            case CalibrationActions.ACTION_START_MAGNETOMETER_CALIBRATION:
                boolean retryOnFailure = data.getBoolean(CalibrationActions.EXTRA_RETRY_ON_FAILURE, false);
                boolean saveAutomatically = data.getBoolean(CalibrationActions.EXTRA_SAVE_AUTOMATICALLY, true);
                int startDelay = data.getInt(CalibrationActions.EXTRA_START_DELAY, 0);
                CommonApiUtils.startMagnetometerCalibration(this, retryOnFailure, saveAutomatically, startDelay);
                return true;

            case CalibrationActions.ACTION_CANCEL_MAGNETOMETER_CALIBRATION:
                CommonApiUtils.cancelMagnetometerCalibration(this);
                return true;

            case CalibrationActions.ACTION_ACCEPT_MAGNETOMETER_CALIBRATION:
                CommonApiUtils.acceptMagnetometerCalibration(this);
                return true;

            //************ Gimbal ACTIONS *************//
            case GimbalActions.ACTION_SET_GIMBAL_ORIENTATION:
                float pitch = data.getFloat(GimbalActions.GIMBAL_PITCH);
                float roll = data.getFloat(GimbalActions.GIMBAL_ROLL);
                float yaw = data.getFloat(GimbalActions.GIMBAL_YAW);
                MavLinkDoCmds.setGimbalOrientation(this, pitch, roll, yaw, listener);
                return true;

            case GimbalActions.ACTION_RESET_GIMBAL_MOUNT_MODE:
            case GimbalActions.ACTION_SET_GIMBAL_MOUNT_MODE:
                int mountMode = data.getInt(GimbalActions.GIMBAL_MOUNT_MODE, MAV_MOUNT_MODE.MAV_MOUNT_MODE_RC_TARGETING);
                Timber.i("Setting gimbal mount mode: %d", mountMode);

                Parameter mountParam = this.parameterManager.getParameter("MNT_MODE");
                if (mountParam == null) {
                    msg_mount_configure msg = new msg_mount_configure();
                    msg.target_system = getSysid();
                    msg.target_component = getCompid();
                    msg.mount_mode = (byte) mountMode;
                    msg.stab_pitch = 0;
                    msg.stab_roll = 0;
                    msg.stab_yaw = 0;
                    getMavClient().sendMavMessage(msg, listener);
                } else {
                    MavLinkParameters.sendParameter(this, "MNT_MODE", 1, mountMode);
                }
                return true;

            default:
                return super.executeAsyncAction(action, listener);
        }
    }

    @Override
    public void onMavLinkMessageReceived(MAVLinkMessage message) {
        int compId = message.compid;
        if (compId != AUTOPILOT_COMPONENT_ID
                && compId != ARTOO_COMPONENT_ID
                && compId != TELEMETRY_RADIO_COMPONENT_ID) {
            return;
        }

        if (!getParameterManager().processMessage(message)) {

            getWaypointManager().processMessage(message);
            getCalibrationSetup().processMessage(message);

            switch (message.msgid) {
                case msg_heartbeat.MAVLINK_MSG_ID_HEARTBEAT:
                    msg_heartbeat msg_heart = (msg_heartbeat) message;
                    checkIfFlying(msg_heart);
                    processState(msg_heart);
                    ApmModes newMode = ApmModes.getMode(msg_heart.custom_mode, getType());
                    getState().setMode(newMode);
                    break;

                case msg_statustext.MAVLINK_MSG_ID_STATUSTEXT:
                    // These are any warnings sent from APM:Copter with
                    // gcs_send_text_P()
                    // This includes important thing like arm fails, prearm fails, low
                    // battery, etc.
                    // also less important things like "erasing logs" and
                    // "calibrating barometer"
                    msg_statustext msg_statustext = (msg_statustext) message;
                    processStatusText(msg_statustext);
                    break;

                case msg_vfr_hud.MAVLINK_MSG_ID_VFR_HUD:
                    processVfrHud((msg_vfr_hud) message);
                    break;

                case msg_mission_current.MAVLINK_MSG_ID_MISSION_CURRENT:
                    getMissionStats().setWpno(((msg_mission_current) message).seq);
                    break;

                case msg_mission_item_reached.MAVLINK_MSG_ID_MISSION_ITEM_REACHED:
                    getMissionStats().setLastReachedWaypointNumber(((msg_mission_item_reached) message).seq);
                    break;

                case msg_nav_controller_output.MAVLINK_MSG_ID_NAV_CONTROLLER_OUTPUT:
                    msg_nav_controller_output m_nav = (msg_nav_controller_output) message;
                    setDisttowpAndSpeedAltErrors(m_nav.wp_dist, m_nav.alt_error, m_nav.aspd_error);
                    break;

                case msg_raw_imu.MAVLINK_MSG_ID_RAW_IMU:
                    msg_raw_imu msg_imu = (msg_raw_imu) message;
                    getMagnetometer().newData(msg_imu);
                    break;

                case msg_radio.MAVLINK_MSG_ID_RADIO:
                    msg_radio m_radio = (msg_radio) message;
                    processSignalUpdate(m_radio.rxerrors, m_radio.fixed, m_radio.rssi,
                            m_radio.remrssi, m_radio.txbuf, m_radio.noise, m_radio.remnoise);
                    break;

                case msg_rc_channels_raw.MAVLINK_MSG_ID_RC_CHANNELS_RAW:
                    rc.setRcInputValues((msg_rc_channels_raw) message);
                    break;

                case msg_servo_output_raw.MAVLINK_MSG_ID_SERVO_OUTPUT_RAW:
                    rc.setRcOutputValues((msg_servo_output_raw) message);
                    break;

                case msg_camera_feedback.MAVLINK_MSG_ID_CAMERA_FEEDBACK:
                    getCamera().newImageLocation((msg_camera_feedback) message);
                    break;

                case msg_mount_status.MAVLINK_MSG_ID_MOUNT_STATUS:
                    processMountStatus((msg_mount_status) message);
                    break;

                case msg_named_value_int.MAVLINK_MSG_ID_NAMED_VALUE_INT:
                    processNamedValueInt((msg_named_value_int) message);
                    break;

                //*************** Magnetometer calibration messages handling *************//
                case msg_mag_cal_progress.MAVLINK_MSG_ID_MAG_CAL_PROGRESS:
                case msg_mag_cal_report.MAVLINK_MSG_ID_MAG_CAL_REPORT:
                    getMagnetometerCalibration().processCalibrationMessage(message);
                    break;

                default:
                    break;
            }
        }

        super.onMavLinkMessageReceived(message);
    }

    protected void processVfrHud(msg_vfr_hud vfrHud) {
        if (vfrHud == null)
            return;

        setAltitudeGroundAndAirSpeeds(vfrHud.alt, vfrHud.groundspeed, vfrHud.airspeed, vfrHud.climb);
    }

    protected void processMountStatus(msg_mount_status mountStatus) {
        footprints.updateMountOrientation(mountStatus);

        Bundle eventInfo = new Bundle(3);
        eventInfo.putFloat(AttributeEventExtra.EXTRA_GIMBAL_ORIENTATION_PITCH, mountStatus.pointing_a / 100f);
        eventInfo.putFloat(AttributeEventExtra.EXTRA_GIMBAL_ORIENTATION_ROLL, mountStatus.pointing_b / 100f);
        eventInfo.putFloat(AttributeEventExtra.EXTRA_GIMBAL_ORIENTATION_YAW, mountStatus.pointing_c / 100f);
        notifyAttributeListener(AttributeEvent.GIMBAL_ORIENTATION_UPDATED, eventInfo);
    }

    private void processNamedValueInt(msg_named_value_int message) {
        if (message == null)
            return;

        switch (message.getName()) {
            case "ARMMASK":
                //Give information about the vehicle's ability to arm successfully.
                ApmModes vehicleMode = getState().getMode();
                if (ApmModes.isCopter(vehicleMode.getType())) {
                    int value = message.value;
                    boolean isReadyToArm = (value & (1 << vehicleMode.getNumber())) != 0;
                    String armReadinessMsg = isReadyToArm ? "READY TO ARM" : "UNREADY FOR ARMING";
                    logMessage(Log.INFO, armReadinessMsg);
                }
                break;
        }
    }

    private void checkIfFlying(msg_heartbeat msg_heart) {
        short systemStatus = msg_heart.system_status;
        boolean wasFlying = getState().isFlying();

        boolean isFlying = systemStatus == MAV_STATE.MAV_STATE_ACTIVE
                || (wasFlying
                && (systemStatus == MAV_STATE.MAV_STATE_CRITICAL || systemStatus == MAV_STATE.MAV_STATE_EMERGENCY));

        getState().setIsFlying(isFlying);
    }

    private void processState(msg_heartbeat msg_heart) {
        checkArmState(msg_heart);
        checkFailsafe(msg_heart);
    }

    private void checkFailsafe(msg_heartbeat msg_heart) {
        boolean failsafe2 = msg_heart.system_status == MAV_STATE.MAV_STATE_CRITICAL
                || msg_heart.system_status == MAV_STATE.MAV_STATE_EMERGENCY;

        if (failsafe2) {
            getState().repeatWarning();
        }
    }

    private void checkArmState(msg_heartbeat msg_heart) {
        getState().setArmed(
                (msg_heart.base_mode & MAV_MODE_FLAG.MAV_MODE_FLAG_SAFETY_ARMED) == MAV_MODE_FLAG.MAV_MODE_FLAG_SAFETY_ARMED);
    }

    protected void processStatusText(msg_statustext statusText) {
        String message = statusText.getText();
        if (TextUtils.isEmpty(message))
            return;

        if (message.startsWith("ArduCopter") || message.startsWith("ArduPlane")
                || message.startsWith("ArduRover") || message.startsWith("Solo")
                || message.startsWith("APM:Copter") || message.startsWith("APM:Plane")
                || message.startsWith("APM:Rover")) {
            setFirmwareVersion(message);
        } else {

            //Try parsing as an error.
            if (!getState().parseAutopilotError(message)) {

                //Relay to the connected client.
                int logLevel;
                switch (statusText.severity) {
                    case APMConstants.Severity.SEVERITY_CRITICAL:
                        logLevel = Log.ERROR;
                        break;

                    case APMConstants.Severity.SEVERITY_HIGH:
                        logLevel = Log.WARN;
                        break;

                    case APMConstants.Severity.SEVERITY_MEDIUM:
                        logLevel = Log.INFO;
                        break;

                    default:
                    case APMConstants.Severity.SEVERITY_LOW:
                        logLevel = Log.VERBOSE;
                        break;

                    case APMConstants.Severity.SEVERITY_USER_RESPONSE:
                        logLevel = Log.DEBUG;
                        break;
                }

                logMessage(logLevel, message);
            }
        }
    }

    public Double getBattDischarge(double battRemain) {
        Parameter battCap = getParameterManager().getParameter("BATT_CAPACITY");
        if (battCap == null || battRemain == -1) {
            return null;
        }
        return (1 - battRemain / 100.0) * battCap.getValue();
    }

    @Override
    protected void processBatteryUpdate(double voltage, double remain, double current) {
        if (battery.getBatteryRemain() != remain) {
            battery.setBatteryDischarge(getBattDischarge(remain));
        }
        super.processBatteryUpdate(voltage, remain, current);
    }
}
