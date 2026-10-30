package com.amdocs.telecom.bss.util;

import java.nio.file.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Explicit operator-owned configuration; never falls back to legacy database credentials. */
public final class BssConfiguration {
    private final Properties values=new Properties();
    public BssConfiguration() {
        Path file=Path.of("tcsms.properties");
        if(Files.isRegularFile(file)) try(Reader reader=Files.newBufferedReader(file,StandardCharsets.UTF_8)) {values.load(reader);}
        catch(IOException ex) {throw new IllegalStateException("Cannot read tcsms.properties");}
    }
    public String get(String key,String fallback) {
        String env=System.getenv("TCSMS_"+key.toUpperCase(Locale.ROOT).replace('.','_'));
        return env!=null?env:values.getProperty(key,fallback);
    }
    public String required(String key) {
        String v=get(key,"");if(v.isBlank() || v.contains("CHANGE_ME")) throw new IllegalArgumentException("Set TCSMS_"+key.toUpperCase(Locale.ROOT).replace('.','_')+" or "+key+" in local tcsms.properties");return v;
    }
}
