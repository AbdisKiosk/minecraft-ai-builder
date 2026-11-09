package me.abdiskiosk.minecraftaibuilder.common;

import java.util.List;
import java.util.Map;

/**
 * @param type            canonical modern name
 * @param properties      property name to allowed values, in a stable order
 * @param ignored         valid modern properties this server accepts but cannot store (1.8 only)
 * @param blockEntity     "sign", "head", ... when the block takes a data object, otherwise null
 */
public record BlockInfo(String type, Map<String, List<String>> properties, List<String> ignored, String blockEntity) {
}
