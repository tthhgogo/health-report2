#!/bin/zsh

set -u
set -o pipefail
unsetopt BG_NICE
umask 077

readonly TEST_SCRIPT="${0:A}"
readonly SOURCE_ROOT="${0:A:h:h}"
readonly SOURCE_WORKER="${SOURCE_ROOT}/scripts/claude-codex-workflow.zsh"
readonly SOURCE_SEQUENCE="${SOURCE_ROOT}/scripts/claude-codex-sequence.zsh"
TEST_ROOT="$(mktemp -d)"
typeset -i PASSED=0

function cleanup() {
    if [[ "${KEEP_TEST_ROOT:-0}" == "1" ]]; then
        print -u2 "[test] 保留临时目录: ${TEST_ROOT}"
        return 0
    fi
    if [[ -n "${TEST_ROOT}" && -d "${TEST_ROOT}" ]]; then
        command rm -rf "${TEST_ROOT}"
    fi
}
trap 'cleanup' EXIT

function fail() {
    print -u2 "[FAIL] $1"
    exit 1
}

function assert_equals() {
    local expected="$1"
    local actual="$2"
    local message="$3"
    if [[ "${expected}" != "${actual}" ]]; then
        fail "${message}: expected=${expected}, actual=${actual}"
    fi
}

function assert_file() {
    local file_path="$1"
    local message="$2"
    if [[ ! -f "${file_path}" ]]; then
        fail "${message}: ${file_path}"
    fi
}

function assert_contains() {
    local file_path="$1"
    local pattern="$2"
    local message="$3"
    if ! grep -q -- "${pattern}" "${file_path}"; then
        fail "${message}: ${file_path} 中没有 ${pattern}"
    fi
}

function assert_not_contains() {
    local file_path="$1"
    local pattern="$2"
    local message="$3"
    if grep -q -- "${pattern}" "${file_path}"; then
        fail "${message}: ${file_path} 中不应出现 ${pattern}"
    fi
}

function wait_for_file() {
    local file_path="$1"
    local message="$2"
    local attempt
    for attempt in {1..150}; do
        if [[ -f "${file_path}" ]]; then
            return 0
        fi
        sleep 0.1
    done
    fail "${message}: ${file_path}"
}

function wait_for_file_contains() {
    local file_path="$1"
    local pattern="$2"
    local message="$3"
    local attempt
    for attempt in {1..150}; do
        if [[ -f "${file_path}" ]] && grep -q -- "${pattern}" "${file_path}"; then
            return 0
        fi
        sleep 0.1
    done
    fail "${message}: ${file_path} 中没有 ${pattern}"
}

function wait_for_pid_exit() {
    local target_pid="$1"
    local message="$2"
    local attempt
    for attempt in {1..100}; do
        if ! kill -0 "${target_pid}" 2>/dev/null; then
            return 0
        fi
        sleep 0.1
    done
    fail "${message}: PID=${target_pid}"
}

function create_fake_repo() {
    local repo="$1"
    mkdir -p "${repo}/scripts" "${repo}/tasks" "${repo}/fake-bin" "${repo}/fake-jdk/bin"
    cp "${SOURCE_WORKER}" "${repo}/scripts/claude-codex-workflow.zsh"
    cp "${SOURCE_SEQUENCE}" "${repo}/scripts/claude-codex-sequence.zsh"
    chmod +x "${repo}/scripts/claude-codex-workflow.zsh" "${repo}/scripts/claude-codex-sequence.zsh"

    print -r -- ".ai-collab/" > "${repo}/.gitignore"
    print -r -- "# 任务一" > "${repo}/tasks/01.md"
    print -r -- "# 任务二" > "${repo}/tasks/02.md"
    print -r -- "baseline" > "${repo}/tracked.txt"

    cat <<'STUB' > "${repo}/fake-jdk/bin/java"
#!/bin/zsh
print -u2 'openjdk version "1.8.0_502"'
exit 0
STUB

    cat <<'STUB' > "${repo}/fake-bin/codex"
#!/bin/zsh
output_file=""
prompt_arg=""
while (( $# > 0 )); do
    prompt_arg="$1"
    if [[ "$1" == "--output-last-message" ]]; then
        output_file="$2"
        shift 2
        continue
    fi
    shift
done
if [[ -n "${FAKE_CODEX_PROMPT_CAPTURE_FILE:-}" ]]; then
    print -r -- "${prompt_arg}" > "${FAKE_CODEX_PROMPT_CAPTURE_FILE}"
fi
if [[ -n "${FAKE_CODEX_WRITE_FILE:-}" ]]; then
    print -r -- "${FAKE_CODEX_WRITE_CONTENT:-fake codex content}" \
        > "${FAKE_CODEX_WRITE_FILE}"
fi
mode="${FAKE_CODEX_MODE:-success}"
counter_file="${FAKE_CODEX_IMPLEMENTATION_COUNT_FILE:-}"
if [[ "${output_file}" == *"codex-fix"* ]]; then
    mode="${FAKE_CODEX_FIX_MODE:-${mode}}"
    counter_file="${FAKE_CODEX_FIX_COUNT_FILE:-}"
fi
if [[ -n "${counter_file}" ]]; then
    count=0
    if [[ -f "${counter_file}" ]]; then
        count="$(<"${counter_file}")"
    fi
    (( count++ ))
    print -r -- "${count}" > "${counter_file}"
fi
if [[ -n "${output_file}" ]]; then
    print -r -- "Codex 假实现完成" > "${output_file}"
fi
if [[ "${mode}" == "block" ]]; then
    if [[ -n "${FAKE_BLOCK_READY_FILE:-}" ]]; then
        print -r -- "$$" > "${FAKE_BLOCK_READY_FILE}"
    fi
    trap 'exit 143' HUP INT TERM
    while true; do
        sleep 0.1
    done
fi
if [[ "${mode}" == "interrupt" ]]; then
    kill -TERM "${PPID}"
    sleep 1
    exit 143
fi
if [[ "${mode}" == "fail" ]]; then
    exit 4
fi
exit 0
STUB

    cat <<'STUB' > "${repo}/fake-bin/claude"
#!/bin/zsh
if [[ "${FAKE_CLAUDE_MODE:-success}" == "block" ]]; then
    if [[ -n "${FAKE_BLOCK_READY_FILE:-}" ]]; then
        print -r -- "$$" > "${FAKE_BLOCK_READY_FILE}"
    fi
    trap 'exit 143' HUP INT TERM
    while true; do
        sleep 0.1
    done
fi
verdict="${FAKE_CLAUDE_VERDICT:-PASS}"
if [[ -n "${FAKE_CLAUDE_PLAN_FILE:-}" ]]; then
    count=0
    if [[ -f "${FAKE_CLAUDE_COUNT_FILE}" ]]; then
        count="$(<"${FAKE_CLAUDE_COUNT_FILE}")"
    fi
    (( count++ ))
    print -r -- "${count}" > "${FAKE_CLAUDE_COUNT_FILE}"
    verdict="$(sed -n "${count}p" "${FAKE_CLAUDE_PLAN_FILE}")"
fi
print -r -- "VERDICT: ${verdict}"
exit 0
STUB

    cat <<'STUB' > "${repo}/fake-bin/mvn"
#!/bin/zsh
if [[ "${FAKE_MVN_MODE:-success}" == "block" ]]; then
    if [[ -n "${FAKE_BLOCK_READY_FILE:-}" ]]; then
        print -r -- "$$" > "${FAKE_BLOCK_READY_FILE}"
    fi
    trap 'exit 143' HUP INT TERM
    while true; do
        sleep 0.1
    done
fi
print -r -- "[INFO] Tests run: ${FAKE_TESTS_RUN:-1}, Failures: 0, Errors: 0, Skipped: 0"
exit "${FAKE_MVN_EXIT:-0}"
STUB

    chmod +x "${repo}/fake-jdk/bin/java" "${repo}/fake-bin/codex" \
        "${repo}/fake-bin/claude" "${repo}/fake-bin/mvn"

    git -C "${repo}" init -q
    git -C "${repo}" config user.name "workflow-test"
    git -C "${repo}" config user.email "workflow-test@example.invalid"
    git -C "${repo}" add .
    git -C "${repo}" commit -qm "baseline"
}

function workflow_env() {
    local repo="$1"
    shift
    env \
        COLLAB_DISABLE_CAFFEINATE=1 \
        COLLAB_JAVA_HOME="${repo}/fake-jdk" \
        PATH="${repo}/fake-bin:${PATH}" \
        "$@"
}

function test_happy_path_and_baseline_backup() {
    local repo="${TEST_ROOT}/happy"
    local run_dir
    create_fake_repo "${repo}"
    run_dir="${repo}/.ai-collab/happy-run"
    print -r -- "user change" > "${repo}/tracked.txt"
    print -r -- "untracked baseline" > "${repo}/user-note.txt"

    workflow_env "${repo}" \
        "${repo}/scripts/claude-codex-workflow.zsh" \
        --run-dir "${run_dir}" "${repo}/tasks/01.md" \
        > "${repo}/happy.log" 2>&1 || fail "正常流程不应失败"

    assert_contains "${run_dir}/workflow-status.txt" "status=PASS" "正常流程状态错误"
    assert_contains "${run_dir}/02-build-summary-r0.txt" "testsExecuted=YES" "测试门禁未识别已执行测试"
    assert_file "${run_dir}/00-baseline-files/tracked.txt" "未备份已跟踪用户修改"
    assert_file "${run_dir}/00-baseline-files/user-note.txt" "未备份未跟踪用户文件"
    assert_file "${run_dir}/00-baseline-git-status.stderr.log" "缺少 Git 状态 stderr 诊断文件"
    assert_file "${run_dir}/00-baseline-git-diff.stderr.log" "缺少 Git 补丁 stderr 诊断文件"
    assert_contains "${run_dir}/00-baseline-files/tracked.txt" "user change" "已跟踪基线备份内容错误"
    assert_contains "${run_dir}/00-baseline-files/user-note.txt" "untracked baseline" "未跟踪基线备份内容错误"
    (( PASSED++ ))
    print "[PASS] 正常流程、测试门禁和基线备份"
}

function test_zero_tests_gate() {
    local repo="${TEST_ROOT}/zero-tests"
    local run_dir
    typeset -i exit_code=0
    create_fake_repo "${repo}"
    run_dir="${repo}/.ai-collab/zero-tests-run"

    workflow_env "${repo}" \
        FAKE_TESTS_RUN=0 \
        MAX_FIX_ROUNDS=0 \
        "${repo}/scripts/claude-codex-workflow.zsh" \
        --run-dir "${run_dir}" "${repo}/tasks/01.md" \
        > "${repo}/zero-tests.log" 2>&1 || exit_code=$?

    assert_equals "2" "${exit_code}" "零测试应被最大轮次门禁拦截"
    assert_contains "${run_dir}/workflow-status.txt" "status=MAX_FIX_ROUNDS_REACHED" "零测试状态错误"
    assert_contains "${run_dir}/02-build-summary-r0.txt" "testsExecuted=NO" "零测试识别错误"
    (( PASSED++ ))
    print "[PASS] 零测试机械门禁"
}

function test_interrupted_worker_resume() {
    local repo="${TEST_ROOT}/resume-worker"
    local run_dir
    local count_file="${TEST_ROOT}/implementation-count.txt"
    local prompt_file="${TEST_ROOT}/resume-worker-prompt.txt"
    local user_pause_file
    local -a resume_backup_dirs
    typeset -i exit_code=0
    create_fake_repo "${repo}"
    run_dir="${repo}/.ai-collab/resume-worker-run"

    workflow_env "${repo}" \
        FAKE_CODEX_MODE=interrupt \
        FAKE_CODEX_IMPLEMENTATION_COUNT_FILE="${count_file}" \
        "${repo}/scripts/claude-codex-workflow.zsh" \
        --run-dir "${run_dir}" "${repo}/tasks/01.md" \
        > "${repo}/interrupted.log" 2>&1 || exit_code=$?

    assert_equals "143" "${exit_code}" "TERM 中断退出码错误"
    assert_contains "${run_dir}/workflow-status.txt" "status=INTERRUPTED_TERM" "中断状态未持久化"
    assert_contains "${run_dir}/workflow-checkpoint.txt" "phase=IMPLEMENTATION_RUNNING" "中断阶段未持久化"

    user_pause_file="${repo}/user-during-pause.txt"
    print -r -- "user content during pause" > "${user_pause_file}"
    print -r -- "tracked user content during pause" > "${repo}/tracked.txt"
    workflow_env "${repo}" \
        FAKE_CODEX_IMPLEMENTATION_COUNT_FILE="${count_file}" \
        FAKE_CODEX_PROMPT_CAPTURE_FILE="${prompt_file}" \
        FAKE_CODEX_WRITE_FILE="${user_pause_file}" \
        FAKE_CODEX_WRITE_CONTENT="codex overwritten content" \
        "${repo}/scripts/claude-codex-workflow.zsh" \
        --resume "${run_dir}" \
        > "${repo}/resumed.log" 2>&1 || fail "单模块断点恢复失败"

    assert_contains "${run_dir}/workflow-status.txt" "status=PASS" "恢复后未完成"
    assert_equals "2" "$(<"${count_file}")" "开发阶段中断后应重新执行 Codex"
    assert_contains "${user_pause_file}" "codex overwritten content" "伪 Codex 未构造覆盖场景"
    resume_backup_dirs=("${run_dir}"/resume-*-files(N))
    assert_equals "1" "${#resume_backup_dirs[@]}" "恢复前应生成一份独立逐文件备份"
    assert_file "${resume_backup_dirs[1]}/user-during-pause.txt" "中断期间新增文件没有恢复备份"
    assert_contains "${resume_backup_dirs[1]}/user-during-pause.txt" \
        "user content during pause" "恢复备份没有保留被覆盖前的用户内容"
    assert_contains "${resume_backup_dirs[1]}/tracked.txt" \
        "tracked user content during pause" "恢复备份没有保留中断期间修改的 tracked 文件"
    assert_contains "${run_dir}/resume-backups.tsv" "resume-" "恢复备份索引不存在或内容异常"
    assert_contains "${prompt_file}" "归属无法确认的任何当前改动，一律按用户改动处理" \
        "恢复提示词没有采用默认保护边界"
    assert_not_contains "${prompt_file}" "不在该清单里的新增改动属于" \
        "恢复提示词仍包含危险归属规则"
    (( PASSED++ ))
    print "[PASS] 单模块 TERM 中断恢复与中断窗口用户文件备份"
}

function test_build_term_interrupt_and_resume() {
    local repo="${TEST_ROOT}/term-build"
    local run_dir
    local ready_file="${TEST_ROOT}/term-build.ready"
    local workflow_pid
    local child_pid
    typeset -i exit_code=0
    create_fake_repo "${repo}"
    run_dir="${repo}/.ai-collab/term-build-run"

    env \
        COLLAB_DISABLE_CAFFEINATE=1 \
        COLLAB_JAVA_HOME="${repo}/fake-jdk" \
        PATH="${repo}/fake-bin:${PATH}" \
        FAKE_MVN_MODE=block \
        FAKE_BLOCK_READY_FILE="${ready_file}" \
        "${repo}/scripts/claude-codex-workflow.zsh" \
        --run-dir "${run_dir}" "${repo}/tasks/01.md" \
        > "${repo}/term-build.log" 2>&1 &
    workflow_pid=$!

    wait_for_file "${ready_file}" "Maven 阻塞阶段未就绪"
    wait_for_file_contains "${run_dir}/workflow-checkpoint.txt" \
        "phase=BUILD_RUNNING" "构建中断阶段未落盘"
    child_pid="$(<"${run_dir}/current-child.pid")"
    kill -TERM "${workflow_pid}"
    wait "${workflow_pid}" || exit_code=$?

    assert_equals "143" "${exit_code}" "BUILD_RUNNING TERM 退出码错误"
    wait_for_pid_exit "${child_pid}" "TERM 后 Maven 子进程仍存活"
    assert_contains "${run_dir}/workflow-status.txt" "status=INTERRUPTED_TERM" "构建中断状态错误"

    workflow_env "${repo}" \
        "${repo}/scripts/claude-codex-workflow.zsh" \
        --resume "${run_dir}" \
        > "${repo}/term-build-resume.log" 2>&1 || fail "BUILD_RUNNING 恢复失败"

    assert_contains "${run_dir}/workflow-status.txt" "status=PASS" "构建恢复后未完成"
    (( PASSED++ ))
    print "[PASS] BUILD_RUNNING TERM 立即终止并恢复"
}

function test_review_term_interrupt_and_resume() {
    local repo="${TEST_ROOT}/term-review"
    local run_dir
    local ready_file="${TEST_ROOT}/term-review.ready"
    local workflow_pid
    local child_pid
    typeset -i exit_code=0
    create_fake_repo "${repo}"
    run_dir="${repo}/.ai-collab/term-review-run"

    env \
        COLLAB_DISABLE_CAFFEINATE=1 \
        COLLAB_JAVA_HOME="${repo}/fake-jdk" \
        PATH="${repo}/fake-bin:${PATH}" \
        FAKE_CLAUDE_MODE=block \
        FAKE_BLOCK_READY_FILE="${ready_file}" \
        "${repo}/scripts/claude-codex-workflow.zsh" \
        --run-dir "${run_dir}" "${repo}/tasks/01.md" \
        > "${repo}/term-review.log" 2>&1 &
    workflow_pid=$!

    wait_for_file "${ready_file}" "Claude 阻塞阶段未就绪"
    wait_for_file_contains "${run_dir}/workflow-checkpoint.txt" \
        "phase=REVIEW_RUNNING" "评审中断阶段未落盘"
    child_pid="$(<"${run_dir}/current-child.pid")"
    kill -TERM "${workflow_pid}"
    wait "${workflow_pid}" || exit_code=$?

    assert_equals "143" "${exit_code}" "REVIEW_RUNNING TERM 退出码错误"
    wait_for_pid_exit "${child_pid}" "TERM 后 Claude 子进程仍存活"
    assert_contains "${run_dir}/workflow-status.txt" "status=INTERRUPTED_TERM" "评审中断状态错误"

    workflow_env "${repo}" \
        "${repo}/scripts/claude-codex-workflow.zsh" \
        --resume "${run_dir}" \
        > "${repo}/term-review-resume.log" 2>&1 || fail "REVIEW_RUNNING 恢复失败"

    assert_contains "${run_dir}/workflow-status.txt" "status=PASS" "评审恢复后未完成"
    (( PASSED++ ))
    print "[PASS] REVIEW_RUNNING TERM 立即终止并恢复"
}

function test_fix_term_interrupt_and_resume() {
    local repo="${TEST_ROOT}/term-fix"
    local run_dir
    local ready_file="${TEST_ROOT}/term-fix.ready"
    local count_file="${TEST_ROOT}/fix-count.txt"
    local workflow_pid
    local child_pid
    typeset -i exit_code=0
    create_fake_repo "${repo}"
    run_dir="${repo}/.ai-collab/term-fix-run"

    env \
        COLLAB_DISABLE_CAFFEINATE=1 \
        COLLAB_JAVA_HOME="${repo}/fake-jdk" \
        PATH="${repo}/fake-bin:${PATH}" \
        FAKE_CLAUDE_VERDICT=CHANGES_REQUIRED \
        FAKE_CODEX_FIX_MODE=block \
        FAKE_CODEX_FIX_COUNT_FILE="${count_file}" \
        FAKE_BLOCK_READY_FILE="${ready_file}" \
        "${repo}/scripts/claude-codex-workflow.zsh" \
        --run-dir "${run_dir}" "${repo}/tasks/01.md" \
        > "${repo}/term-fix.log" 2>&1 &
    workflow_pid=$!

    wait_for_file "${ready_file}" "Codex 修复阻塞阶段未就绪"
    wait_for_file_contains "${run_dir}/workflow-checkpoint.txt" \
        "phase=FIX_RUNNING" "修复中断阶段未落盘"
    child_pid="$(<"${run_dir}/current-child.pid")"
    kill -TERM "${workflow_pid}"
    wait "${workflow_pid}" || exit_code=$?

    assert_equals "143" "${exit_code}" "FIX_RUNNING TERM 退出码错误"
    wait_for_pid_exit "${child_pid}" "TERM 后 Codex 修复子进程仍存活"
    assert_contains "${run_dir}/workflow-status.txt" "status=INTERRUPTED_TERM" "修复中断状态错误"

    workflow_env "${repo}" \
        FAKE_CLAUDE_VERDICT=PASS \
        FAKE_CODEX_FIX_COUNT_FILE="${count_file}" \
        "${repo}/scripts/claude-codex-workflow.zsh" \
        --resume "${run_dir}" \
        > "${repo}/term-fix-resume.log" 2>&1 || fail "FIX_RUNNING 恢复失败"

    assert_contains "${run_dir}/workflow-status.txt" "status=PASS" "修复恢复后未完成"
    assert_equals "2" "$(<"${count_file}")" "修复阶段中断后应重新执行同一轮 Codex"
    assert_not_contains "${repo}/term-fix.log" "build_prefix=" "控制台泄漏局部变量"
    assert_not_contains "${repo}/term-fix.log" "review_prefix=" "控制台泄漏局部变量"
    assert_not_contains "${repo}/term-fix.log" "fix_prefix=" "控制台泄漏局部变量"
    (( PASSED++ ))
    print "[PASS] FIX_RUNNING TERM 立即终止并重跑同轮修复"
}

function test_sigkill_orphan_guard_and_resume() {
    local repo="${TEST_ROOT}/sigkill-orphan"
    local run_dir
    local ready_file="${TEST_ROOT}/sigkill-orphan.ready"
    local workflow_pid
    local child_pid
    typeset -i killed_exit=0
    typeset -i resume_exit=0
    create_fake_repo "${repo}"
    run_dir="${repo}/.ai-collab/sigkill-orphan-run"

    env \
        COLLAB_DISABLE_CAFFEINATE=1 \
        COLLAB_JAVA_HOME="${repo}/fake-jdk" \
        PATH="${repo}/fake-bin:${PATH}" \
        FAKE_CODEX_MODE=block \
        FAKE_BLOCK_READY_FILE="${ready_file}" \
        "${repo}/scripts/claude-codex-workflow.zsh" \
        --run-dir "${run_dir}" "${repo}/tasks/01.md" \
        > "${repo}/sigkill.log" 2>&1 &
    workflow_pid=$!

    wait_for_file "${ready_file}" "SIGKILL 场景 Codex 未就绪"
    child_pid="$(<"${run_dir}/current-child.pid")"
    kill -KILL "${workflow_pid}"
    wait "${workflow_pid}" || killed_exit=$?
    assert_equals "137" "${killed_exit}" "工作流 SIGKILL 退出码错误"
    if ! kill -0 "${child_pid}" 2>/dev/null; then
        fail "SIGKILL 后测试孤儿 Codex 未保持存活，场景无效"
    fi

    workflow_env "${repo}" \
        "${repo}/scripts/claude-codex-workflow.zsh" \
        --resume "${run_dir}" \
        > "${repo}/sigkill-concurrent-resume.log" 2>&1 || resume_exit=$?

    assert_equals "4" "${resume_exit}" "孤儿存活时恢复应拒绝启动"
    assert_contains "${repo}/sigkill-concurrent-resume.log" "仍有子进程运行" "孤儿拒绝提示缺失"

    kill -TERM "${child_pid}" 2>/dev/null || true
    wait_for_pid_exit "${child_pid}" "手工终止后孤儿 Codex 仍存活"

    workflow_env "${repo}" \
        "${repo}/scripts/claude-codex-workflow.zsh" \
        --resume "${run_dir}" \
        > "${repo}/sigkill-resume.log" 2>&1 || fail "孤儿结束后的恢复失败"

    assert_contains "${run_dir}/workflow-status.txt" "status=PASS" "SIGKILL 恢复后未完成"
    (( PASSED++ ))
    print "[PASS] SIGKILL 后拒绝与孤儿并发并可安全恢复"
}

function test_sequence_block_and_resume() {
    local repo="${TEST_ROOT}/resume-sequence"
    local sequence_dir
    local completed_file
    local plan_file="${TEST_ROOT}/claude-plan.txt"
    local count_file="${TEST_ROOT}/claude-count.txt"
    typeset -i exit_code=0
    create_fake_repo "${repo}"
    {
        print -r -- "tasks/01.md"
        print -r -- "tasks/02.md"
    } > "${repo}/tasks/order.txt"
    {
        print -r -- "PASS"
        print -r -- "BLOCKED"
        print -r -- "PASS"
    } > "${plan_file}"

    workflow_env "${repo}" \
        FAKE_CLAUDE_PLAN_FILE="${plan_file}" \
        FAKE_CLAUDE_COUNT_FILE="${count_file}" \
        "${repo}/scripts/claude-codex-sequence.zsh" \
        "${repo}/tasks/order.txt" \
        > "${repo}/sequence-first.log" 2>&1 || exit_code=$?

    assert_equals "3" "${exit_code}" "第二模块 BLOCKED 应停止顺序流程"
    sequence_dir="$(find "${repo}/.ai-collab/sequences" -mindepth 1 -maxdepth 1 -type d | head -n 1)"
    assert_contains "${sequence_dir}/sequence-state.txt" "currentIndex=2" "顺序断点模块索引错误"
    assert_contains "${sequence_dir}/sequence-state.txt" "status=STOPPED" "顺序阻塞状态错误"

    # 模拟模块 2 已追加完成台账、但 currentIndex 尚未推进时进程崩溃。
    completed_file="${sequence_dir}/completed-modules.tsv"
    printf 'crash-window\t2\t%s\tPASS\n' "${repo}/tasks/02.md" >> "${completed_file}"
    assert_equals "2" "$(wc -l < "${completed_file}" | tr -d '[:space:]')" "崩溃窗口构造失败"

    workflow_env "${repo}" \
        FAKE_CLAUDE_PLAN_FILE="${plan_file}" \
        FAKE_CLAUDE_COUNT_FILE="${count_file}" \
        "${repo}/scripts/claude-codex-sequence.zsh" \
        --resume "${sequence_dir}" \
        > "${repo}/sequence-resume.log" 2>&1 || fail "顺序流程恢复失败"

    assert_contains "${sequence_dir}/sequence-state.txt" "status=PASS" "顺序恢复后未完成"
    assert_equals "2" "$(wc -l < "${completed_file}" | tr -d '[:space:]')" "完成模块台账恢复后重复"
    assert_equals "1" "$(awk -F '\t' '$2 == 2 { count++ } END { print count + 0 }' "${completed_file}")" \
        "同一模块只能保留一条完成记录"
    (( PASSED++ ))
    print "[PASS] 第二模块阻塞恢复与完成台账幂等"
}

function test_sequence_term_interrupt_and_resume() {
    local repo="${TEST_ROOT}/term-sequence"
    local sequence_dir
    local ready_file="${TEST_ROOT}/term-sequence.ready"
    local sequence_pid
    local module_pid
    local codex_pid
    typeset -i exit_code=0
    create_fake_repo "${repo}"
    {
        print -r -- "tasks/01.md"
        print -r -- "tasks/02.md"
    } > "${repo}/tasks/order.txt"

    env \
        COLLAB_DISABLE_CAFFEINATE=1 \
        COLLAB_JAVA_HOME="${repo}/fake-jdk" \
        PATH="${repo}/fake-bin:${PATH}" \
        FAKE_CODEX_MODE=block \
        FAKE_BLOCK_READY_FILE="${ready_file}" \
        "${repo}/scripts/claude-codex-sequence.zsh" \
        "${repo}/tasks/order.txt" \
        > "${repo}/term-sequence.log" 2>&1 &
    sequence_pid=$!

    wait_for_file "${ready_file}" "顺序总控中的 Codex 未就绪"
    sequence_dir="$(find "${repo}/.ai-collab/sequences" -mindepth 1 -maxdepth 1 -type d | head -n 1)"
    wait_for_file "${sequence_dir}/current-child.pid" "顺序总控未记录模块 PID"
    wait_for_file "${sequence_dir}/modules/001/current-child.pid" "模块未记录 Codex PID"
    module_pid="$(<"${sequence_dir}/current-child.pid")"
    codex_pid="$(<"${sequence_dir}/modules/001/current-child.pid")"

    kill -TERM "${sequence_pid}"
    wait "${sequence_pid}" || exit_code=$?

    assert_equals "143" "${exit_code}" "顺序总控 TERM 退出码错误"
    wait_for_pid_exit "${module_pid}" "TERM 后模块工作流仍存活"
    wait_for_pid_exit "${codex_pid}" "TERM 后模块 Codex 仍存活"
    assert_contains "${sequence_dir}/sequence-state.txt" "status=INTERRUPTED_TERM" "顺序中断状态错误"
    assert_contains "${sequence_dir}/modules/001/workflow-status.txt" \
        "status=INTERRUPTED_TERM" "模块中断状态错误"

    workflow_env "${repo}" \
        "${repo}/scripts/claude-codex-sequence.zsh" \
        --resume "${sequence_dir}" \
        > "${repo}/term-sequence-resume.log" 2>&1 || fail "顺序总控 TERM 后恢复失败"

    assert_contains "${sequence_dir}/sequence-state.txt" "status=PASS" "顺序 TERM 恢复后未完成"
    assert_equals "2" "$(wc -l < "${sequence_dir}/completed-modules.tsv" | tr -d '[:space:]')" \
        "顺序 TERM 恢复后的完成模块数量错误"
    (( PASSED++ ))
    print "[PASS] 顺序总控 TERM 终止整条子流程并恢复"
}

function test_head_change_rebaseline_and_missing_baseline() {
    local repo="${TEST_ROOT}/rebaseline"
    local run_dir
    local new_head
    local second_head
    local -a rebaseline_patches
    typeset -i blocked_exit=0
    typeset -i changed_exit=0
    typeset -i missing_exit=0
    create_fake_repo "${repo}"
    run_dir="${repo}/.ai-collab/rebaseline-run"

    workflow_env "${repo}" \
        FAKE_CLAUDE_VERDICT=BLOCKED \
        "${repo}/scripts/claude-codex-workflow.zsh" \
        --run-dir "${run_dir}" "${repo}/tasks/01.md" \
        > "${repo}/rebaseline-blocked.log" 2>&1 || blocked_exit=$?
    assert_equals "3" "${blocked_exit}" "重基线前置 BLOCKED 场景错误"

    print -r -- "accepted commit" >> "${repo}/tracked.txt"
    git -C "${repo}" add tracked.txt
    git -C "${repo}" commit -qm "accepted external commit"
    new_head="$(git -C "${repo}" rev-parse HEAD)"

    workflow_env "${repo}" \
        "${repo}/scripts/claude-codex-workflow.zsh" \
        --resume "${run_dir}" \
        > "${repo}/head-changed.log" 2>&1 || changed_exit=$?
    assert_equals "4" "${changed_exit}" "HEAD 变化时普通恢复应失败"
    assert_contains "${repo}/head-changed.log" "--rebaseline" "HEAD 变化提示缺少逃生口"

    workflow_env "${repo}" \
        FAKE_CLAUDE_VERDICT=PASS \
        "${repo}/scripts/claude-codex-workflow.zsh" \
        --rebaseline "${run_dir}" \
        > "${repo}/rebaseline-resume.log" 2>&1 || fail "--rebaseline 未能恢复流程"

    assert_contains "${run_dir}/workflow-status.txt" "status=PASS" "重基线后未完成"
    assert_equals "${new_head}" "$(<"${run_dir}/00-baseline-head.txt")" "新基线 HEAD 错误"
    assert_file "${run_dir}/rebaseline-history.tsv" "缺少重基线审计记录"
    assert_contains "${run_dir}/rebaseline-history.tsv" "${new_head}" "重基线审计未记录新 HEAD"

    # 连续再次重建基线，验证秒级时间戳相同也不会覆盖上一份产物。
    print -r -- "second accepted commit" >> "${repo}/tracked.txt"
    git -C "${repo}" add tracked.txt
    git -C "${repo}" commit -qm "second accepted external commit"
    second_head="$(git -C "${repo}" rev-parse HEAD)"
    workflow_env "${repo}" \
        FAKE_CLAUDE_VERDICT=PASS \
        "${repo}/scripts/claude-codex-workflow.zsh" \
        --rebaseline "${run_dir}" \
        > "${repo}/rebaseline-second.log" 2>&1 || fail "第二次 --rebaseline 未能恢复流程"

    rebaseline_patches=("${run_dir}"/rebaseline-*-git-diff.patch(N))
    assert_equals "2" "${#rebaseline_patches[@]}" "连续重基线快照发生覆盖"
    assert_equals "2" "$(wc -l < "${run_dir}/rebaseline-history.tsv" | tr -d '[:space:]')" \
        "重基线台账与快照数量不一致"
    assert_equals "${second_head}" "$(<"${run_dir}/00-baseline-head.txt")" "第二次重基线 HEAD 错误"
    assert_contains "${run_dir}/review-baseline-diff-path.txt" "rebaseline-" \
        "评审比较基线没有切换到重基线快照"

    mv "${run_dir}/00-baseline-head.txt" "${run_dir}/00-baseline-head.txt.missing"
    workflow_env "${repo}" \
        "${repo}/scripts/claude-codex-workflow.zsh" \
        --resume "${run_dir}" \
        > "${repo}/missing-baseline.log" 2>&1 || missing_exit=$?
    assert_equals "4" "${missing_exit}" "基线文件缺失时恢复应失败"
    assert_contains "${repo}/missing-baseline.log" "缺少 00-baseline-head.txt" "基线缺失错误不明确"
    (( PASSED++ ))
    print "[PASS] HEAD 变化、显式重基线与基线缺失保护"
}

function test_rebaseline_after_original_head_pruned() {
    local repo="${TEST_ROOT}/rebaseline-pruned"
    local run_dir
    local original_head
    local old_branch
    local rewritten_head
    local -a original_head_error_logs
    typeset -i blocked_exit=0
    typeset -i resume_exit=0
    create_fake_repo "${repo}"
    run_dir="${repo}/.ai-collab/rebaseline-pruned-run"
    original_head="$(git -C "${repo}" rev-parse HEAD)"
    old_branch="$(git -C "${repo}" symbolic-ref --short HEAD)"

    workflow_env "${repo}" \
        FAKE_CLAUDE_VERDICT=BLOCKED \
        "${repo}/scripts/claude-codex-workflow.zsh" \
        --run-dir "${run_dir}" "${repo}/tasks/01.md" \
        > "${repo}/pruned-blocked.log" 2>&1 || blocked_exit=$?
    assert_equals "3" "${blocked_exit}" "原始 HEAD 清理测试的 BLOCKED 前置场景错误"

    git -C "${repo}" checkout -q --orphan rewritten
    git -C "${repo}" commit -qm "rewritten root"
    git -C "${repo}" branch -D "${old_branch}" >/dev/null
    git -C "${repo}" reflog expire --expire=now --all
    git -C "${repo}" gc --prune=now
    rewritten_head="$(git -C "${repo}" rev-parse HEAD)"
    if git -C "${repo}" cat-file -e "${original_head}^{commit}" 2>/dev/null; then
        fail "测试前置失败：原始 HEAD 仍可解析"
    fi

    workflow_env "${repo}" \
        "${repo}/scripts/claude-codex-workflow.zsh" \
        --resume "${run_dir}" \
        > "${repo}/pruned-normal-resume.log" 2>&1 || resume_exit=$?
    assert_equals "4" "${resume_exit}" "历史改写后普通恢复应拒绝 HEAD 变化"

    workflow_env "${repo}" \
        FAKE_CLAUDE_VERDICT=PASS \
        "${repo}/scripts/claude-codex-workflow.zsh" \
        --rebaseline "${run_dir}" \
        > "${repo}/pruned-rebaseline.log" 2>&1 \
        || fail "原始 HEAD 被清理后 --rebaseline 不应失败"

    assert_contains "${run_dir}/workflow-status.txt" "status=PASS" "原始 HEAD 清理后重建基线未完成"
    assert_equals "${rewritten_head}" "$(<"${run_dir}/00-baseline-head.txt")" "清理场景的新基线错误"
    assert_equals "${rewritten_head}" "$(<"${run_dir}/00-original-head.txt")" "累计补丁基点未重置"
    assert_file "${run_dir}/original-head-reset-history.tsv" "缺少累计补丁基点重置审计"
    assert_contains "${run_dir}/original-head-reset-history.tsv" "${original_head}" "重置审计缺少旧原始 HEAD"
    assert_contains "${run_dir}/original-head-reset-history.tsv" "${rewritten_head}" "重置审计缺少新基线"
    assert_contains "${repo}/pruned-rebaseline.log" "累计补丁基点重置为新基线" "重置告警不明确"
    original_head_error_logs=("${run_dir}"/rebaseline-*-original-head-check.stderr.log(N))
    assert_equals "1" "${#original_head_error_logs[@]}" "原始 HEAD 校验 stderr 文件数量错误"
    assert_contains "${original_head_error_logs[1]}" "${original_head}" "原始 HEAD 校验未保留 Git 真实错误"
    (( PASSED++ ))
    print "[PASS] 原始 HEAD 被清理后显式重建基线"
}

zsh -n "${SOURCE_WORKER}" || fail "单模块脚本语法错误"
zsh -n "${SOURCE_SEQUENCE}" || fail "顺序脚本语法错误"
test_happy_path_and_baseline_backup
test_zero_tests_gate
test_interrupted_worker_resume
test_build_term_interrupt_and_resume
test_review_term_interrupt_and_resume
test_fix_term_interrupt_and_resume
test_sigkill_orphan_guard_and_resume
test_sequence_block_and_resume
test_sequence_term_interrupt_and_resume
test_head_change_rebaseline_and_missing_baseline
test_rebaseline_after_original_head_pruned
print "[PASS] 共 ${PASSED} 组自动流程测试通过"
