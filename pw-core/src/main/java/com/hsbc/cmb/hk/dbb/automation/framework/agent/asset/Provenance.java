package com.hsbc.cmb.hk.dbb.automation.framework.agent.asset;

/**
 * 资产溯源（设计 §4.6 版本与溯源 / R9 审计）。
 *
 * <p>记录生成版本、来源指纹、生成器（人工/LLM 供应商+模型版本）、生成时间，
 * 使资产可审计、可回滚。任何来源（含 LLM）一律视为草稿，不自动可信。</p>
 *
 * @param sourceFingerprint 来源页面/需求版本指纹（如 {@code "page:sha256:ab12…"}）
 * @param generator         生成器标识（如 {@code "manual"} / {@code "llm:openai:gpt-4o:2026-11"}）
 * @param generatedAtEpoch  生成时间（epoch millis）
 */
public record Provenance(String sourceFingerprint, String generator, long generatedAtEpoch) {

    /** 人工/录制产出的溯源（确定性、无 LLM）。 */
    public static Provenance manual(String sourceFingerprint) {
        return new Provenance(sourceFingerprint, "manual", System.currentTimeMillis());
    }

    /** LLM 产出的溯源（携带供应商与模型版本，供审计与供应商治理 R9/§8.1）。 */
    public static Provenance llm(String sourceFingerprint, String supplier, String modelVersion) {
        return new Provenance(sourceFingerprint, "llm:" + supplier + ":" + modelVersion, System.currentTimeMillis());
    }
}
