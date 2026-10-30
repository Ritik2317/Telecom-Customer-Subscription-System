package com.amdocs.telecom.bss.validation;

import java.math.*;
import java.util.*;

public final class Rules {
    private Rules() { }
    public static void require(boolean condition,String message) { if(!condition) throw new IllegalArgumentException(message); }
    public static String text(String value,int max,String label) {
        require(value!=null && !value.trim().isEmpty() && value.trim().length()<=max,label+" is required (maximum "+max+" characters)");
        require(value.chars().noneMatch(c->c<32 || c==127),label+" must not contain control characters");
        return value.trim();
    }
    public static String email(String value) {
        String s=text(value,120,"Email").toLowerCase(Locale.ROOT);
        require(s.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"),"Enter a valid email address"); return s;
    }
    public static String mobile(String value) {
        String s=text(value,20,"Mobile number").replace("-","").replace(" ","");
        require(s.matches("\\+[1-9][0-9]{7,14}"),"Use an international mobile number, for example +919876543210"); return s;
    }
    public static BigDecimal nonnegative(BigDecimal value,String label) {
        require(value!=null && value.signum()>=0 && value.compareTo(new BigDecimal("999999999"))<=0,label+" must be between 0 and 999999999"); return value;
    }
    public static BigDecimal money(BigDecimal value) { return value.setScale(2,RoundingMode.HALF_UP); }
    public static String choice(String value,String... choices) {
        String s=text(value,30,"Selection").toUpperCase(Locale.ROOT);
        require(Arrays.asList(choices).contains(s),"Choose one of "+Arrays.toString(choices)); return s;
    }
}
