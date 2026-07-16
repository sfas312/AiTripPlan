package managerAgent.evaluate;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Rule-based quality scorer for trip planning outputs.
 *
 * Each dimension is scored 0-2:
 *   0 = missing or wrong
 *   1 = mentioned but incomplete
 *   2 = clear, reasonable, actionable
 *
 * overall_score = sum of all dimensions (0-10)
 */
public class QualityScorer {

    // ── Dimension scoring ──────────────────────────────────────────────

    public static int scoreRoute(String content) {
        if (content == null || content.isBlank()) return 0;
        int hits = 0;

        // origin & destination
        if (hasCityPair(content)) hits++;
        // route description
        if (containsAny(content, "路线", "高速", "国道", "省道", "途径", "途经", "行驶", "驾车",
                "route", "highway", "drive")) hits++;
        // distance or duration
        if (containsAny(content, "公里", "km", "小时", "分钟", "里程", "耗时", "距离",
                "distance", "duration")) hits++;
        // toll or highway name
        if (containsAny(content, "过路费", "高速费", "收费站", "ETC", "toll", "G15", "G4", "G50",
                "G60", "G65", "G42", "G2", "G1", "G30", "G56", "G72")) hits++;

        if (hits >= 3) return 2;
        if (hits >= 2) return 1;
        return 0;
    }

    public static int scoreTrip(String content) {
        if (content == null || content.isBlank()) return 0;
        int hits = 0;

        // daily plan
        if (containsAny(content, "第.*天", "第一天", "第二天", "Day", "上午", "下午", "行程",
                "日程", "安排", "day", "Day 1", "Day 2")) hits++;
        // hotel / accommodation
        if (containsAny(content, "酒店", "住宿", "民宿", "旅店", "客栈", "宾馆", "hotel",
                "住", "入住", "accommodation")) hits++;
        // food / dining
        if (containsAny(content, "餐饮", "美食", "餐厅", "推荐", "吃", "小吃", "特色",
                "火锅", "海鲜", "早餐", "午餐", "晚餐", "food", "restaurant")) hits++;
        // attraction
        if (containsAny(content, "景点", "景区", "公园", "博物馆", "古镇", "海滩", "山",
                "寺", "湖", "园", "attraction", "visit")) hits++;

        if (hits >= 3) return 2;
        if (hits >= 2) return 1;
        return 0;
    }

    public static int scoreBudget(String content, String prompt) {
        if (content == null || content.isBlank()) return 0;
        int hits = 0;

        // budget mentioned
        if (containsAny(content, "预算", "费用", "花费", "元", "￥", "¥", "价格", "budget",
                "cost", "price")) hits++;
        // numeric amounts
        if (hasNumericAmount(content)) hits++;
        // budget breakdown
        if (containsAny(content, "住宿.*元", "餐饮.*元", "油费", "过路费.*元", "门票.*元",
                "交通.*元", "总计", "合计", "小计", "预算", "总费用")) hits++;

        // Check budget compliance
        int budgetLimit = extractBudgetFromPrompt(prompt);
        boolean withinBudget = true;
        if (budgetLimit > 0) {
            int mentionedBudget = extractMaxBudgetFromContent(content);
            if (mentionedBudget > 0 && mentionedBudget > budgetLimit) {
                withinBudget = false;
            }
        }

        if (hits >= 2 && withinBudget) return 2;
        if (hits >= 1) return 1;
        return 0;
    }

    public static int scoreConstraint(String content, String prompt) {
        if (content == null || content.isBlank()) return 0;
        int hits = 0;

        // origin
        String origin = extractCity(prompt, "从", "到");
        if (origin != null && content.contains(origin)) hits++;
        // destination
        String dest = extractCity(prompt, "到", "的");
        if (dest == null) dest = extractCity(prompt, "去", "的");
        if (dest == null) dest = extractCity(prompt, "至", "的");
        if (dest != null && content.contains(dest)) hits++;
        // days
        if (matchDays(prompt, content)) hits++;
        // self-driving
        if (prompt.contains("自驾") && (content.contains("自驾") || content.contains("开车")
                || content.contains("驾车") || content.contains("高速"))) hits++;
        // special preferences
        if (hasSpecialPreferenceMatch(prompt, content)) hits++;

        if (hits >= 4) return 2;
        if (hits >= 2) return 1;
        return 0;
    }

    public static int scoreContentQuality(String content) {
        // Structural quality: proper formatting, sections, coherence
        if (content == null || content.isBlank()) return 0;
        int score = 0;

        // Has line breaks / paragraphs
        if (content.contains("\n")) score++;
        // Has numbered sections
        if (Pattern.compile("\\d+[.、)]").matcher(content).find()) score++;
        // Content length is reasonable (not too short)
        if (content.length() >= 100) score++;
        // Content is not too verbose (good signal-to-noise)
        if (content.length() <= 2000) score++;
        // Has specific names (not generic)
        if (Pattern.compile("[一-龥]{2,}(?:酒店|景区|公园|博物馆|餐厅|高速|公路|路|街|区)")
                .matcher(content).find()) score++;

        if (score >= 3) return 2;
        if (score >= 1) return 1;
        return 0;
    }

    // ── Overall scoring ────────────────────────────────────────────────

    public static QualityResult evaluate(String routeContent, String tripContent,
                                         String prompt,
                                         boolean routeLinkSuccess, boolean tripLinkSuccess) {

        int routeScore = routeLinkSuccess ? scoreRoute(routeContent) : 0;
        int tripScore = tripLinkSuccess ? scoreTrip(tripContent) : 0;
        String combinedContent = routeContent + "\n" + tripContent;
        int budgetScore = scoreBudget(combinedContent, prompt);
        int constraintScore = scoreConstraint(combinedContent, prompt);
        int contentQuality = scoreContentQuality(combinedContent);

        int overallScore = routeScore + tripScore + budgetScore + constraintScore + contentQuality; // 0-10

        boolean routeQualitySuccess = routeScore >= 1;
        boolean tripQualitySuccess = tripScore >= 1;
        boolean budgetSatisfied = budgetScore >= 1;
        boolean constraintSatisfied = constraintScore >= 1;
        boolean finalQualitySuccess = routeLinkSuccess && tripLinkSuccess
                && routeQualitySuccess && tripQualitySuccess
                && budgetSatisfied && constraintSatisfied;

        return new QualityResult(
                routeScore, tripScore, budgetScore, constraintScore, contentQuality,
                overallScore,
                routeQualitySuccess, tripQualitySuccess,
                budgetSatisfied, constraintSatisfied,
                finalQualitySuccess);
    }

    // ── Helper methods ─────────────────────────────────────────────────

    private static boolean containsAny(String text, String... keywords) {
        String lower = text.toLowerCase();
        for (String kw : keywords) {
            if (lower.contains(kw.toLowerCase())) return true;
        }
        return false;
    }

    private static boolean hasCityPair(String text) {
        // Check for pattern like "X到Y" or "X至Y" with city names
        return Pattern.compile("[一-龥]{2,4}[到至→-][一-龥]{2,4}").matcher(text).find()
                || Pattern.compile("起点.*终点").matcher(text).find()
                || Pattern.compile("出发.*到达").matcher(text).find();
    }

    private static boolean hasNumericAmount(String text) {
        return Pattern.compile("\\d+\\s*[元块]").matcher(text).find()
                || Pattern.compile("[￥¥]\\s*\\d+").matcher(text).find();
    }

    private static int extractBudgetFromPrompt(String prompt) {
        Pattern p = Pattern.compile("预算.*?(\\d+)\\s*元");
        java.util.regex.Matcher m = p.matcher(prompt);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
    }

    private static int extractMaxBudgetFromContent(String content) {
        // Find the largest budget-like number in the content
        Pattern p = Pattern.compile("(\\d+)\\s*元");
        java.util.regex.Matcher m = p.matcher(content);
        int maxBudget = -1;
        while (m.find()) {
            try {
                int val = Integer.parseInt(m.group(1));
                if (val > maxBudget && val >= 100) {
                    maxBudget = val;
                }
            } catch (NumberFormatException ignored) {}
        }
        return maxBudget;
    }

    private static String extractCity(String prompt, String prefix, String suffix) {
        int start = prompt.indexOf(prefix);
        if (start < 0) return null;
        start += prefix.length();
        int end = prompt.indexOf(suffix, start);
        if (end < 0) return null;
        String city = prompt.substring(start, end).trim();
        // Remove common suffixes
        city = city.replaceAll("[市省区县]$", "");
        return city.length() >= 2 && city.length() <= 6 ? city : null;
    }

    private static boolean matchDays(String prompt, String content) {
        Pattern p = Pattern.compile("(\\d+)\\s*日");
        java.util.regex.Matcher pm = p.matcher(prompt);
        if (pm.find()) {
            String days = pm.group(1);
            return content.contains(days + "日") || content.contains(days + "天")
                    || content.contains("第" + days + "天");
        }
        return true; // can't determine days, don't penalize
    }

    private static boolean hasSpecialPreferenceMatch(String prompt, String content) {
        Map<String, List<String>> preferenceMap = new LinkedHashMap<>();
        preferenceMap.put("亲子", List.of("亲子", "孩子", "儿童", "家庭", "小朋友"));
        preferenceMap.put("情侣", List.of("情侣", "浪漫", "夜景", "约会"));
        preferenceMap.put("美食", List.of("美食", "小吃", "特色", "火锅", "海鲜", "餐厅", "地道"));
        preferenceMap.put("文化", List.of("文化", "历史", "博物馆", "古", "传统", "陶瓷"));
        preferenceMap.put("登山", List.of("登山", "山", "徒步", "爬"));
        preferenceMap.put("海边", List.of("海", "沙滩", "海边", "海滨"));
        preferenceMap.put("冬季", List.of("冬季", "雪", "冰", "保暖", "防滑"));

        for (Map.Entry<String, List<String>> entry : preferenceMap.entrySet()) {
            String prefKey = entry.getKey();
            List<String> prefKeywords = entry.getValue();
            if (containsAny(prompt, prefKey)) {
                // Prompt mentions this preference; check if content addresses it
                boolean matched = false;
                for (String kw : prefKeywords) {
                    if (content.contains(kw)) {
                        matched = true;
                        break;
                    }
                }
                if (matched) return true;
            }
        }
        // No special preference or couldn't verify — give benefit of doubt
        return !hasSpecialPreferenceInPrompt(prompt);
    }

    private static boolean hasSpecialPreferenceInPrompt(String prompt) {
        return containsAny(prompt, "亲子", "情侣", "美食", "文化", "登山", "海边", "冬季",
                "家庭", "夜景", "陶瓷", "沙滩", "雪乡");
    }

    // ── Result class ───────────────────────────────────────────────────

    public record QualityResult(
            int routeScore,
            int tripScore,
            int budgetScore,
            int constraintScore,
            int contentQualityScore,
            int overallScore,
            boolean routeQualitySuccess,
            boolean tripQualitySuccess,
            boolean budgetSatisfied,
            boolean constraintSatisfied,
            boolean finalQualitySuccess
    ) {
        @Override
        public String toString() {
            return String.format(
                    "QualityResult{route=%d/2, trip=%d/2, budget=%d/2, constraint=%d/2, content=%d/2, overall=%d/10, finalQuality=%s}",
                    routeScore, tripScore, budgetScore, constraintScore, contentQualityScore,
                    overallScore, finalQualitySuccess);
        }
    }
}
