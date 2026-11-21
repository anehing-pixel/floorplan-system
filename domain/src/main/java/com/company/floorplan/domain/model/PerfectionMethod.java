package com.company.floorplan.domain.model;

/**
 * 担保登记的对抗要件方式（perfection method）。
 *
 * <p><b>为什么必须单列这个字段</b>：日本让渡担保的优先顺位不按"谁先签合同"排，
 * 而是按<b>谁先具备对抗要件</b>排。同一天、同一台车，可能一方做了引渡し、
 * 另一方做了登记——登记者优先。而 2025 年新法
 * （《譲渡担保契約及び所有権留保契約に関する法律》，2025-05-30 成立、
 * 2025-06-06 公布，<b>施行日最晚 2027-12-06</b>）进一步明确：
 * <b>占有改定被劣后于登记等公示性高的方式</b>（同法第 36 条）。
 * 这意味着过去"靠占有改定先占位"的做法将来可能不再保得住优先顺位，
 * 内部台账必须能算出每一笔的对抗要件，才能判断风险。</p>
 *
 * <p>本枚举同时兼容新旧法：新法施行前后的做法都能记录，不需要改表。</p>
 */
public enum PerfectionMethod {

    /**
     * 登记（法务省 / 法务局）。
     * <p>公示性最高，<b>新法下优先顺位优于占有改定</b>。
     * 让渡担保实务中的主要完善方式；登记有 5 年期限且不可续展。</p>
     */
    REGISTRATION("登记（法务省）"),

    /** 引渡し：实际交付占有，由金融公司（或其指定的保管人）现实占有标的。 */
    POSSESSION_TRANSFER("引渡し（实际交付占有）"),

    /**
     * 占有改定：原始占有人（通常是经销商）继续占有，
     * 但对外表示代金融公司占有。
     * <p>过去实务中常用，但<b>缺乏外部公示性</b>，新法下已明确劣后于登记。</p>
     */
    CONSTRUCTIVE_TRANSFER("占有改定"),

    /**
     * 所有権留保（所有权保留）。
     * <p>针对<b>买卖价金债权</b>的担保，2025 年新法下
     * <b>无需对抗要件即可成立</b>（同法"机动车价款债权担保"特别规则），
     * 故单列以示区别。</p>
     */
    RETENTION("所有权留保（无需对抗要件）"),

    /** 无对抗要件（其他方式）。 */
    NONE("无");

    private final String label;

    PerfectionMethod(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /**
     * 该方式在新法下的公示性等级（数值越大公示性越强）。
     *
     * <p>用于台账排序与风险提示：占有改定在 2025 年新法下已劣后于登记，
     * 若同一台车上存在占有改定与登记并存，应提示核查顺位。</p>
     *
     * @return 公示性等级（0=无，1=弱，2=中，3=强）
     */
    public int publicityLevel() {
        return switch (this) {
            case REGISTRATION -> 3;
            case POSSESSION_TRANSFER -> 2;
            case CONSTRUCTIVE_TRANSFER -> 1;
            case RETENTION, NONE -> 0;
        };
    }

    /**
     * 该方式在 2025 年新法下是否已明确劣后于其他方式。
     *
     * @return true 表示占有改定（新法第 36 条）
     */
    public boolean subordinatingAfter2025Act() {
        return this == CONSTRUCTIVE_TRANSFER;
    }
}
