package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.model.PickSnapshot;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.model.StepRec;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.model.PageOp;

final class RolePickerCodeAssembler {

    private static final Logger log = LoggerFactory.getLogger(RolePickerCodeAssembler.class);
    private static final Gson GSON = new Gson();
    private static final Type MAP_STRING_OBJECT_TYPE = new TypeToken<Map<String, Object>>() {}.getType();

    private static final java.util.concurrent.atomic.AtomicInteger CLOSE_SEQ =
            new java.util.concurrent.atomic.AtomicInteger();

    static LinkedHashMap<String, String> buildPageClassCode(List<RoleEntry> entries, String packageName,
                                             String defaultPageClass, String[] nlsFiles) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        if (entries == null || entries.isEmpty()) return out;
        LinkedHashMap<String, List<RoleEntry>> entriesByPage = new LinkedHashMap<>();
        for (RoleEntry e : entries) {
            String pc = (e.getPageClass() == null || e.getPageClass().isEmpty()) ? defaultPageClass : e.getPageClass();
            entriesByPage.computeIfAbsent(pc, k -> new ArrayList<>()).add(e);
        }
        for (Map.Entry<String, List<RoleEntry>> en : entriesByPage.entrySet()) {
            out.put(en.getKey(), RoleElementPageGenerator.generate(en.getValue(), packageName, en.getKey(), nlsFiles));
        }
        return out;
    }

    static PickSnapshot snapWithAutoStep(PickSnapshot snap) {
        if (snap == null) return snap;
        if (snap.steps != null && !snap.steps.isEmpty()) return snap;
        if (snap.entries == null || snap.entries.isEmpty()) return snap;
        List<RoleEntry> all = new ArrayList<>(snap.entries);
        String rootPc = "";
        for (RoleEntry e : all) {
            String pc = e.getPageClass();
            if (pc != null && !pc.isEmpty()) { rootPc = pc; break; }
        }
        if (rootPc.isEmpty()) rootPc = (snap.pageClass == null) ? "" : snap.pageClass;

        List<RoleEntry> sorted = new ArrayList<>(all);
        List<StepRec> steps = new ArrayList<>();
        steps.add(new StepRec(rootPc, sorted));
        return new PickSnapshot(snap.pageClass, snap.entries, steps, snap.ops);
    }

    static LinkedHashMap<String, String> buildStepCode(PickSnapshot snap, String packageName, String stepClassName) {
        return buildStepCode(snap, packageName, stepClassName, null);
    }

    static LinkedHashMap<String, String> buildStepCode(PickSnapshot snap, String packageName, String stepClassName,
            LinkedHashMap<String, List<java.util.Map<String, java.util.List<Integer>>>> stepRoundNosByPage) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        if (snap == null) return out;

        try {
            java.util.Set<String> listBases = RoleElementPageGenerator.listGroupBaseSigs(snap.entries);
            if (!listBases.isEmpty()) {
                RoleElementPageGenerator.markListGroups(snap.entries, listBases);
                if (snap.steps != null) {
                    for (StepRec st : snap.steps) {
                        if (st != null && st.picks != null) {
                            RoleElementPageGenerator.markListGroups(st.picks, listBases);
                        }
                    }
                }
            }
        } catch (Exception ignore) {
            RolePickerQuiet.ignore("RolePickerCodeAssembler#markListGroups", ignore);
        }

        if (snap.entries != null && !snap.entries.isEmpty() && snap.steps != null) {
                java.util.Map<String, RoleEntry> byLocKey = new java.util.HashMap<>();
                for (RoleEntry en : snap.entries) {
                    String lk = RoleElementPageGenerator.locatorKey(en);
                    if (lk != null && !lk.isEmpty()) byLocKey.put(lk, en);
                }
                if (!byLocKey.isEmpty()) {
                    List<StepRec> enrichedSteps = new ArrayList<>(snap.steps.size());
                    for (StepRec st : snap.steps) {
                        if (st == null) { enrichedSteps.add(st); continue; }
                        List<RoleEntry> enrichedPicks = new ArrayList<>(st.picks == null ? 0 : st.picks.size());
                        if (st.picks != null) for (RoleEntry p : st.picks) {
                            String lk = (p == null) ? "" : RoleElementPageGenerator.locatorKey(p);
                            RoleEntry mem = (lk.isEmpty()) ? null : byLocKey.get(lk);
                            if (mem != null && p != null) {

                                if (needsReplaceByMemory(p, mem)) {
                                    p = mem;
                                } else {

                                    int recovered = (p.getIndex() >= 0) ? p.getIndex()
                                            : RolePickerPickParser.parseIndexFromSig(p.getSigKey());
                                    if (recovered < 0 && mem.getIndex() >= 0) p.setIndex(mem.getIndex());
                                    if (!p.isList() && mem.isList()) p.setList(true);
                                }
                            }
                            enrichedPicks.add(p);
                        }
                        enrichedSteps.add(new StepRec(st.pageClass, enrichedPicks));
                    }
                    snap = new PickSnapshot(snap.pageClass, snap.entries, enrichedSteps, snap.ops);
                }
            }
        String curClass = (snap.pageClass == null) ? "" : snap.pageClass;
        LinkedHashMap<String, List<RoleEntry>> entriesByPage = new LinkedHashMap<>();
        for (RoleEntry e : snap.entries) {
            String pc = (e.getPageClass() == null || e.getPageClass().isEmpty()) ? curClass : e.getPageClass();
            entriesByPage.computeIfAbsent(pc, k -> new ArrayList<>()).add(e);
        }

        java.util.Set<String> aliveKeys = new java.util.HashSet<>();
        for (RoleEntry e : snap.entries) {
            String lk = RoleElementPageGenerator.locatorKey(e);
            if (lk != null && !lk.isEmpty()) aliveKeys.add(lk);
        }
        int droppedPicks = 0, droppedSteps = 0;
        LinkedHashMap<String, List<List<RoleEntry>>> stepsByPage = new LinkedHashMap<>();
        LinkedHashMap<String, List<String>> droppedByPage = new LinkedHashMap<>();
        if (snap.steps != null) for (StepRec st : snap.steps) {

            String pc = curClass;
            List<RoleEntry> picks = st.picks;
            if (picks != null && !aliveKeys.isEmpty()) {
                List<RoleEntry> kept = new ArrayList<>(picks.size());
                for (RoleEntry e : picks) {
                    if (e == null) continue;
                    String lk = RoleElementPageGenerator.locatorKey(e);

                    if (lk == null || lk.isEmpty() || aliveKeys.contains(lk)) kept.add(e);
                    else {
                        droppedPicks++;

                        droppedByPage.computeIfAbsent(pc, k -> new ArrayList<>())
                                .add((e.getName() == null || e.getName().isEmpty())
                                        ? lk : lk + " (" + e.getName() + ")");

                        if (RolePickerConstants.STRATEGY_I18N.equals(e.getStrategy()) || (e.getStrategy() != null
                                && RoleElementPicker.LOCATOR_IDENTITY_STRATEGIES.contains(e.getStrategy()))) {
                            log.info("[picker][drop-diag] step element removed by reconciliation: strategy={}, name={}, lk={}, count={}, index={}, aliveKeys(i18n class)={}",
                                    e.getStrategy(), e.getName(), lk, e.getCount(), e.getIndex(),
                                    aliveKeys.stream().filter(k -> k != null && k.startsWith(e.getStrategy() + ":")).limit(10).collect(java.util.stream.Collectors.toList()));
                        }
                    }
                }
                picks = kept;
            }
            if (picks == null || picks.isEmpty()) {

                if (st.picks != null && !st.picks.isEmpty()) droppedSteps++;
                continue;
            }
            stepsByPage.computeIfAbsent(pc, k -> new ArrayList<>()).add(picks);
            entriesByPage.computeIfAbsent(pc, k -> new ArrayList<>());
        }
        if (droppedPicks > 0 || droppedSteps > 0) {

            log.warn("[picker] pre-generation reconciliation: dropped {} deleted element reference(s) {}, discarded {} empty step(s)",
                    droppedPicks, droppedByPage, droppedSteps);
        }
        LinkedHashMap<String, List<String>> opsByPage = new LinkedHashMap<>();
        if (snap.ops != null) for (PageOp op : snap.ops) {
            String pc = (op.pageClass == null || op.pageClass.isEmpty()) ? curClass : op.pageClass;
            opsByPage.computeIfAbsent(pc, k -> new ArrayList<>()).add(op.op);
        }
        if (stepsByPage.isEmpty() && opsByPage.isEmpty()) return out;
        return RoleElementStepGenerator.generatePerPage(stepsByPage, entriesByPage, opsByPage,
                new RoleElementStepGenerator.ViewNotes(stepRoundNosByPage),
                packageName, stepClassName);
    }

    static void appendCloseOpStep(Page closed, String pageClass,
                                          Map<Page, String> snapshots) {
        try {
            String json = snapshots.get(closed);
            java.util.Map<String, Object> m = (json != null && !json.isEmpty())
                    ? GSON.fromJson(json, MAP_STRING_OBJECT_TYPE) : null;
            if (m == null) m = new java.util.LinkedHashMap<String, Object>();
            java.util.List<java.util.Map<String, Object>> steps;
            Object st = m.get("steps");
            if (st instanceof java.util.List) {
                @SuppressWarnings("unchecked")
                java.util.List<java.util.Map<String, Object>> tmp = (java.util.List<java.util.Map<String, Object>>) st;
                steps = tmp;
            } else {
                steps = new java.util.ArrayList<java.util.Map<String, Object>>();
                m.put("steps", steps);
            }

            boolean dup = false;
            for (Object s : steps) {
                if (s instanceof java.util.Map && pageClass.equals(((java.util.Map<?, ?>) s).get("pageClass"))
                        && hasClosePick((java.util.Map<?, ?>) s)) { dup = true; break; }
            }
            if (dup) return;
            java.util.Map<String, Object> step = new java.util.LinkedHashMap<String, Object>();
            step.put("pageClass", pageClass);
            java.util.List<java.util.Map<String, Object>> picks = new java.util.ArrayList<java.util.Map<String, Object>>();
            java.util.Map<String, Object> closePick = new java.util.LinkedHashMap<String, Object>();
            closePick.put("_closeOp", Boolean.TRUE);
            closePick.put("_pageClass", pageClass);
            closePick.put("_sig", "__close_" + CLOSE_SEQ.incrementAndGet());
            closePick.put("tag", "close");
            picks.add(closePick);
            step.put("picks", picks);
            steps.add(step);
            snapshots.put(closed, GSON.toJson(m));
        } catch (Exception ignore) {  }
    }

    static boolean hasClosePick(java.util.Map<?, ?> step) {
        Object ps = step.get("picks");
        if (ps instanceof java.util.List) {
            for (Object p : (java.util.List<?>) ps) {
                if (p instanceof java.util.Map && Boolean.TRUE.equals(((java.util.Map<?, ?>) p).get("_closeOp"))) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean needsReplaceByMemory(RoleEntry p, RoleEntry mem) {
        if (p == null || mem == null || p == mem) return false;

        if (!java.util.Objects.equals(p.getChecked(), mem.getChecked())) return true;
        if (!java.util.Objects.equals(p.getSetCheckedTarget(), mem.getSetCheckedTarget())) return true;

        List<String> inFp = p.getFramePath();
        List<String> memFp = mem.getFramePath();
        boolean inEmpty = (inFp == null || inFp.isEmpty());
        boolean memEmpty = (memFp == null || memFp.isEmpty());
        if (inEmpty && !memEmpty) return true;
        if (!inEmpty && !memEmpty && !inFp.equals(memFp)) return true;

        if (!p.isDialog() && mem.isDialog()) return true;

        if (!p.isPopup() && mem.isPopup()) return true;
        return false;
    }

    static LinkedHashMap<String, String> buildAssertCode(PickSnapshot snap, String packageName) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        if (snap == null || snap.steps == null || snap.steps.isEmpty()) return out;
        String curClass = (snap.pageClass == null) ? "" : snap.pageClass;

        Set<String> aliveKeys = new HashSet<>();
        for (RoleEntry e : snap.entries) {
            String lk = RoleElementPageGenerator.locatorKey(e);
            if (lk != null && !lk.isEmpty()) aliveKeys.add(lk);
        }
        LinkedHashMap<String, List<RoleEntry>> entriesByPage = new LinkedHashMap<>();
        for (RoleEntry e : snap.entries) {
            String pc = (e.getPageClass() == null || e.getPageClass().isEmpty()) ? curClass : e.getPageClass();
            entriesByPage.computeIfAbsent(pc, k -> new ArrayList<>()).add(e);
        }
        LinkedHashMap<String, List<List<RoleEntry>>> stepsByPage = new LinkedHashMap<>();

        LinkedHashMap<String, List<Integer>> panelNosByPage = new LinkedHashMap<>();
        int panelStepNo = 0;
        for (StepRec st : snap.steps) {
            if (st == null) continue;
            panelStepNo++;
            String pc = (st.pageClass == null || st.pageClass.isEmpty()) ? curClass : st.pageClass;
            if (st.picks != null) {
                List<RoleEntry> picks = new ArrayList<>(st.picks.size());
                for (RoleEntry e : st.picks) {
                    if (e == null) continue;
                    String lk = RoleElementPageGenerator.locatorKey(e);
                    if (lk != null && !lk.isEmpty() && !aliveKeys.isEmpty() && !aliveKeys.contains(lk)) continue;
                    picks.add(e);
                }
                if (!picks.isEmpty()) {
                    stepsByPage.computeIfAbsent(pc, k -> new ArrayList<>()).add(picks);
                    panelNosByPage.computeIfAbsent(pc, k -> new ArrayList<>()).add(panelStepNo);
                }
            }
        }

        java.util.LinkedHashSet<String> allPages = new java.util.LinkedHashSet<>();
        for (String k : entriesByPage.keySet()) {
            if (k != null && !k.isEmpty()) allPages.add(k);
        }
        for (String k : stepsByPage.keySet()) {
            if (k != null && !k.isEmpty()) allPages.add(k);
        }
        LinkedHashMap<String, java.util.Map<String, String>> keyToFieldByPage = new LinkedHashMap<>();
        LinkedHashMap<String, String> assertPageVars = new LinkedHashMap<>();
        for (String cn : allPages) {
            java.util.Map<String, String> m = new LinkedHashMap<>();
            for (RoleElementPageGenerator.GeneratedField s
                    : RoleElementPageGenerator.assignFields(entriesByPage.getOrDefault(cn, new ArrayList<>()))) {
                m.put(RoleElementPageGenerator.locatorKey(s.entry), s.fieldName);
            }
            keyToFieldByPage.put(cn, m);
            assertPageVars.put(cn, RoleElementAssertionGenerator.pageVarOf(cn));
        }
        for (java.util.Map.Entry<String, List<List<RoleEntry>>> en : stepsByPage.entrySet()) {
            String pc = en.getKey();
            List<List<String>> stepLines = new ArrayList<>();
            for (List<RoleEntry> step : en.getValue()) {

                List<java.util.Map.Entry<Integer, RoleEntry>> seqEntries = new java.util.ArrayList<>();
                for (RoleEntry e : step) {
                    if (e == null || e.isCloseOp()) continue;
                    List<Integer> nos = e.getPickNos();
                    if (nos != null && !nos.isEmpty()) {
                        for (int no : nos) {
                            if (no > 0) seqEntries.add(java.util.Map.entry(no, e));
                        }
                    } else {
                        int seq = e.getSeq();
                        seqEntries.add(java.util.Map.entry(seq > 0 ? seq : Integer.MAX_VALUE, e));
                    }
                }
                seqEntries.sort(java.util.Comparator.comparingInt(java.util.Map.Entry<Integer, RoleEntry>::getKey));
                List<RoleEntry> ordered = new ArrayList<>(seqEntries.size());
                for (java.util.Map.Entry<Integer, RoleEntry> se : seqEntries) {
                    ordered.add(se.getValue());
                }
                List<String> lines = new ArrayList<>(ordered.size());
                for (RoleEntry e : ordered) {
                    String lk = RoleElementPageGenerator.locatorKey(e);
                    if (lk == null) continue;

                    String epc = (e.getPageClass() == null || e.getPageClass().isEmpty()) ? pc : e.getPageClass();
                    String fieldPage = null;
                    String field = null;
                    java.util.Map<String, String> own = keyToFieldByPage.get(epc);
                    if (own != null && own.containsKey(lk)) {
                        fieldPage = epc;
                        field = own.get(lk);
                    }
                    if (field == null) {

                        for (java.util.Map.Entry<String, java.util.Map<String, String>> pe : keyToFieldByPage.entrySet()) {
                            String f = pe.getValue().get(lk);
                            if (f != null) {
                                fieldPage = pe.getKey();
                                field = f;
                                break;
                            }
                        }
                    }
                    if (field == null) continue;

                    lines.add(RoleElementAssertionGenerator.assertStatement(
                            RoleElementAssertionGenerator.pageVarOf(fieldPage == null ? epc : fieldPage), field, e));
                }
                stepLines.add(lines);
            }

            out.put(pc, RoleElementAssertionGenerator.renderClass(
                    packageName, pc, stepLines, assertPageVars, panelNosByPage.get(pc)));
        }
        return out;
    }
}
