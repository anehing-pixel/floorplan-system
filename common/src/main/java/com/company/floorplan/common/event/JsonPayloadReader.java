package com.company.floorplan.common.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * payload JSON 的只读取数器（W0-1，共享层唯一口径）。
 *
 * <p><b>为什么放共享层并公开</b>：{@code outbox_events.payload} 是 TEXT 存的 JSON，发布端要校验它、
 * 消费端要读它。若发布端与消费端各写一套解析（或各自用字符串 {@code contains("\"vin\"")} 判断），
 * 就会出现"发布端认为合法、消费端读不出"的分裂口径——本项目在担保权属上踩过同类坑（Q27）。
 * 字段存在性与类型的判定收敛到这一个类，两侧共用。</p>
 *
 * <p><b>不做的事</b>：不缓存、不校验业务口径（金额是否合理、状态是否允许）、不抛业务异常码。
 * 读不到就返回 null，由调用方决定"这条数据我该失败还是跳过"。</p>
 */
public final class JsonPayloadReader {

    /** ObjectMapper 线程安全，建一次即可；消费发生在调度线程，不能每条事件 new 一个。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonPayloadReader() {
    }

    /**
     * 解析 payload 为 JSON 对象。
     *
     * <p>非法 JSON / 空串 / 非对象一律抛 {@link IllegalArgumentException}：
     * 这类数据是发布方的编程错误，不该被当成"业务上没有值"静默处理。
     * dispatcher 把异常定义为消费失败（记 attempts、游标不前进），坏 payload 因此会显式暴露。</p>
     */
    private static JsonNode tree(String payload) {
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("事件 payload 为空，无法解析");
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(payload);
        } catch (Exception e) {
            throw new IllegalArgumentException("事件 payload 不是合法 JSON：" + payload, e);
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("事件 payload 不是 JSON 对象：" + payload);
        }
        return node;
    }

    /** 读字符串字段；字段不存在或为 JSON null 时返回 null（数字按文本返回，便于比对 VIN）。 */
    public static String readString(String payload, String key) {
        JsonNode v = tree(payload).get(key);
        return v == null || v.isNull() ? null : v.asText();
    }

    /** 读整数字段（金额、ID）；字段不存在或形状不是整数返回 null，不做四舍五入猜测。 */
    public static Long readLong(String payload, String key) {
        JsonNode v = tree(payload).get(key);
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.canConvertToLong()) {
            return v.asLong();
        }
        // 纯数字字符串（"12345"）在跨语言写入时常见，按整数接受；其余形状返回 null 交给调用方判失败。
        String text = v.asText();
        if (text != null && text.matches("-?\\d+")) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 读布尔字段；字段不存在 / JSON null / 不是布尔值返回 null，不做"非空即真"的猜测。
     *
     * <p>{@code PAYMENT_MATCHED} 的 {@code settled} 决定消费方是否回补额度：
     * 把字符串 {@code "false"} 误判成 {@code true} 会直接多放额度，所以这里不猜。</p>
     */
    public static Boolean readBoolean(String payload, String key) {
        JsonNode v = tree(payload).get(key);
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isBoolean()) {
            return v.asBoolean();
        }
        String text = v.asText();
        if ("true".equalsIgnoreCase(text)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(text)) {
            return Boolean.FALSE;
        }
        return null;
    }

    /**
     * 校验 payload 必含 {@code vin} 与 {@code traceId} 且都非空白，不满足即抛异常。
     *
     * <p>这是 V21 与 {@code OutboxPublisher} 注释里已经约定的硬规则：缺 vin 消费端定位不到车辆，
     * 缺 traceId 跨服务排障断链。发布端在写库<b>之前</b>调用，让错误回到生产方栈上，
     * 而不是留下一条永远消费不掉的脏事件。</p>
     */
    public static void requireVinAndTraceId(String payload) {
        JsonNode node = tree(payload);
        JsonNode vin = node.get("vin");
        JsonNode trace = node.get("traceId");
        if (!isPresent(vin)) {
            throw new IllegalArgumentException("事件 payload 缺少必填字段 vin（V21/契约要求）：" + payload);
        }
        if (!isPresent(trace)) {
            throw new IllegalArgumentException("事件 payload 缺少必填字段 traceId（跨服务排障要求）：" + payload);
        }
    }

    /** 字段存在、非 JSON null、且文本不为空白，才算"有值"。 */
    private static boolean isPresent(JsonNode v) {
        return v != null && !v.isNull() && !v.asText().isBlank();
    }
}
