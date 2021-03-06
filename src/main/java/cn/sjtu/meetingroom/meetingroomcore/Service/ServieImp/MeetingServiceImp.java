package cn.sjtu.meetingroom.meetingroomcore.Service.ServieImp;

import cn.sjtu.meetingroom.meetingroomcore.Dao.MeetingRepository;
import cn.sjtu.meetingroom.meetingroomcore.Dao.MeetingRoomRepository;
import cn.sjtu.meetingroom.meetingroomcore.Dao.TimeSliceRepository;
import cn.sjtu.meetingroom.meetingroomcore.Dao.UserRepository;
import cn.sjtu.meetingroom.meetingroomcore.Domain.Meeting;
import cn.sjtu.meetingroom.meetingroomcore.Domain.MeetingRoom;
import cn.sjtu.meetingroom.meetingroomcore.Domain.TimeSlice;
import cn.sjtu.meetingroom.meetingroomcore.Domain.User;
import cn.sjtu.meetingroom.meetingroomcore.Service.MeetingRoomService;
import cn.sjtu.meetingroom.meetingroomcore.Service.MeetingService;
import cn.sjtu.meetingroom.meetingroomcore.Util.Status;
import cn.sjtu.meetingroom.meetingroomcore.Util.Util;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static cn.sjtu.meetingroom.meetingroomcore.Util.Util.generateAttendantNum;

@Service
public class MeetingServiceImp implements MeetingService {
    @Autowired
    MeetingRepository meetingRepository;
    @Autowired
    MeetingRoomRepository meetingRoomRepository;
    @Autowired
    TimeSliceRepository timeSliceRepository;
    @Autowired
    UserRepository userRepository;
    @Autowired
    MeetingRoomService meetingRoomService;

    @Value("${meeting.attendantNum.size}")
    int RandomNumberSize;

    public List<Meeting> showAll(){
        return meetingRepository.findAll();
    }

    public Meeting findById(String id){
        return meetingRepository.findMeetingById(id);
    }

    public List<Meeting> findByDate(String date, List<Meeting> meetings){
        List<Meeting> res = new ArrayList<>();
        for (Meeting meeting : meetings) {
            if (meeting.getDate().equals(date)) res.add(meeting);
        }
        return res;
    }
    public List<Meeting> findByRoomId(String roomId, List<Meeting> meetings){
        List<Meeting> res = new ArrayList<>();
        for (Meeting meeting : meetings) {
            if (meeting.getRoomId().equals(roomId)) res.add(meeting);
        }
        return res;

    }
    public List<Meeting> findByTime(Integer time, List<Meeting> meetings){
        List<Meeting> res = new ArrayList<>();
        int latestTime = Integer.MAX_VALUE;
        Meeting chosenMeeting = null;
        for (Meeting meeting : meetings){
            int startTime = meeting.getStartTime(), endTime = meeting.getEndTime();
            if (startTime <= time && time < endTime) {
                res.add(meeting); return res;
            }
            else if (startTime >= time && time < latestTime) {
                latestTime = time; chosenMeeting = meeting;
            }
        }
        res.add(chosenMeeting);
        return res;
    }

    public List<Meeting> findByStatus(Status status, List<Meeting> meetings){
        List<Meeting> res = new ArrayList<>();
        for (Meeting meeting : meetings){
            if (meeting.getStatus().equals(status)) res.add(meeting);
        }
        return res;
    }

    public List<Meeting> findByLocation(String location, List<Meeting> meetings) {
        List<Meeting> res = new ArrayList<>();
        for (Meeting meeting : meetings){
            if (Util.compare(meeting.getLocation(), location)) res.add(meeting);
        }
        return res;
    }

    @Transactional
    public Meeting add(Meeting meeting){
        //TODO add the case that there is not enough space the meeting
        try {
            meetingRepository.save(meeting);
            completeMeetingAttributes(meeting);
            if (!modifyTimeSlice(meeting, meeting.getId())) throw new Exception();
            return meetingRepository.save(meeting);
        }
        catch (Exception e) {
            meetingRepository.delete(meeting);
            return null;
        }
    }


    @Transactional
    public Meeting attend(String attendantNum, String userId){
        //TODO Awake the host to know it
        Meeting meeting = getMeetingByAttendantNumOrId(attendantNum);
        if (meeting != null) {
            Map<String, String> attendants = meeting.getAttendants();
            if (!attendants.containsKey(userId) && userId != null){
                attendants.put(userId, "");
                meeting.setTimestamp(Util.getTimeStamp());
                meetingRepository.save(meeting);
            }
        }
        return meeting;
    }

    public void cancelMeeting(String id){
        Meeting meeting = meetingRepository.findMeetingById(id);
        meeting.setStatus(Status.Cancelled);
        meetingRepository.save(meeting);
        modifyTimeSlice(meeting, null);
        //TODO Awake the user waiting for the meeting
    }

    public List<User> findAttendants(String id) {
        Meeting meeting = meetingRepository.findMeetingById(id);
        List<User> users = new ArrayList<>();
        for (String uid : meeting.getAttendants().keySet()) {
            users.add(userRepository.findUserById(uid));
        }
        return users;
    }

    @Transactional
    public void exitFromMeeting(String id, String userId){
        Meeting meeting = meetingRepository.findMeetingById(id);
        meeting.getAttendants().remove(userId);
        meeting.setTimestamp(Util.getTimeStamp());
        meetingRepository.save(meeting);
    }

    @Transactional
    public boolean modify(Meeting meeting, String id) {
        Meeting origin = meetingRepository.findMeetingById(id);
        if (meeting == null || origin == null || meeting.getTimestamp() < origin.getTimestamp()) return false;
        meeting.setTimestamp(Util.getTimeStamp());
        //MODIFY THE TIME
        if (isTimeModified(meeting, origin)){
            modifyMeetingTime(meeting, origin);
        }
        meetingRepository.save(meeting);
        return true;
    }

    private boolean modifyMeetingTime(Meeting meeting, Meeting origin) {
        modifyTimeSlice(origin, null);
        if (!modifyTimeSlice(meeting, meeting.getId())) {
            modifyTimeSlice(origin, origin.getId());
            return true;
        }
        return false;
    }

    @Override
    public Meeting intelligentlyFindMeeting(Meeting origin, List<MeetingRoom> meetingRooms) {
        if (meetingRooms.isEmpty()) return null;
        meetingRooms.sort((x, y)->{
            TimeSlice a = timeSliceRepository.findTimeSliceByDateLikeAndRoomIdLike(origin.getDate(), x.getId());
            TimeSlice b = timeSliceRepository.findTimeSliceByDateLikeAndRoomIdLike(origin.getDate(), x.getId());
            return countTimeSlices(b) - countTimeSlices(a);
        });
        origin.setRoomId(meetingRooms.get(0).getId());
        return origin;
    }

    private int countTimeSlices(TimeSlice timeSlice) {
        List<String> timeSlices = timeSlice.getTimeSlice();
        int count = 0;
        for (String s : timeSlices) {
            if (s == null || s.isEmpty()) count++;
        }
        return count;
    }

    private void completeMeetingAttributes(Meeting meeting) {
        meeting.setLocation(meetingRoomRepository.findMeetingRoomById(meeting.getRoomId()).getLocation());
        meeting.setAttendants(generateAttendants(meeting.getHostId()));
        meeting.setAttendantNum(generateAttendantNum(RandomNumberSize));
        meeting.setStatus(Status.Pending);
        meeting.setTimestamp(Util.getTimeStamp());
    }

    private Map<String, String> generateAttendants(String hostId){
        Map<String, String> res = new HashMap<>();
        res.put(hostId, "");
        return res;
    }

    private Meeting getMeetingByAttendantNumOrId(String attendantNum) {
        return isAttendantNum(attendantNum) ? meetingRepository.findMeetingByAttendantNumLikeAndStatusLike(attendantNum, Status.Pending)
                : meetingRepository.findMeetingById(attendantNum);
    }

    private boolean isAttendantNum(String attendantNum){
        return attendantNum.length() == RandomNumberSize;
    }

    private boolean isTimeModified(Meeting meeting, Meeting origin){
        return !(meeting.getDate().equals(origin.getDate()) && meeting.getStartTime() == origin.getStartTime() && meeting.getEndTime() == origin.getEndTime());
    }

    private boolean modifyTimeSlice(Meeting meeting, String id) {
        TimeSlice timeSlice = timeSliceRepository.findTimeSliceByDateLikeAndRoomIdLike(meeting.getDate(), meeting.getRoomId());
        if (!checkTimeSlice(meeting.getStartTime(), meeting.getEndTime(), timeSlice.getTimeSlice(), id)) return false;
        putTimeSlice(meeting.getStartTime(), meeting.getEndTime(), id, timeSlice.getTimeSlice());
        timeSliceRepository.save(timeSlice);
        return true;
    }

    private void putTimeSlice(int startTime, int endTime, String id, List<String> timeSlices) {
        for (int i=startTime; i<endTime; ++i) timeSlices.set(i, id);
    }

    private boolean checkTimeSlice(int startTime, int endTime, List<String> timeSlices, String id) {
        for (int i=startTime; i<endTime; ++i) {
            if (timeSlices.get(i) != null && id != null) return false;
            if (timeSlices.get(i) == null && id == null) return false;
        }
        return true;
    }
}
