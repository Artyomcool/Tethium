package dev.tethium.desktop;

import java.time.*;
import java.time.format.DateTimeFormatter;

final class ChatTime {
    static String label(long timestamp){
        if(timestamp<=0)return "";
        var time=Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault());var today=LocalDate.now();
        String pattern=time.toLocalDate().equals(today)?"HH:mm":time.getYear()==today.getYear()?"dd.MM · HH:mm":"dd.MM.yyyy · HH:mm";
        return " <span class='time'>· "+time.format(DateTimeFormatter.ofPattern(pattern))+"</span>";
    }
}
