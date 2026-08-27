#!/bin/zsh

set -u
set -o pipefail
umask 077

readonly WORKFLOW_SCRIPT="${0:A}"
readonly WORKFLOW_ROOT="${0:A:h:h}"
readonly COLLAB_ROOT="${WORKFLOW_ROOT}/.ai-collab"
readonly WORKFLOW_LOCK_DIR="${COLLAB_ROOT}/workflow.lock"
readonly SEQUENCE_LOCK_DIR="${COLLAB_ROOT}/sequence.lock"
readonly DEFAULT_JAVA_HOME="/Users/will/Library/Java/JavaVirtualMachines/corretto-1.8.0_502/Contents/Home"
readonly MAX_FIX_ROUNDS_VALUE="${MAX_FIX_ROUNDS:-2}"
readonly COLLAB_JAVA_HOME_VALUE="${COLLAB_JAVA_HOME:-${DEFAULT_JAVA_HOME}}"
readonly REQUIRE_TESTS_VALUE="${REQUIRE_TESTS:-1}"

RUN_DIR=""
TASK_FILE_ABS=""
BASELINE_HEAD_SHA=""
CURRENT_PHASE="INIT"
typeset -i CURRENT_ROUND=0
LATEST_SUMMARY=""
BUILD_LOG=""
BUILD_SUMMARY=""
REVIEW_FILE=""
typeset -i BUILD_EXIT=0
LOCK_ACQUIRED=0
typeset -i CURRENT_CHILD_PID=0
CURRENT_CHILD_KIND=""
ORIGINAL_HEAD_SHA=""
REBASELINE_REQUESTED=0
build_prefix=""
review_prefix=""
fix_prefix=""
verdict=""
RESUME_BACKUP_PREFIX=""
RESUME_BACKUP_MANIFEST=""
RESUME_BACKUP_ROOT=""
REVIEW_BASELINE_STATUS=""
REVIEW_BASELINE_DIFF=""

function usage() {
    print -u2 "用法："
    print -u2 "  ${WORKFLOW_SCRIPT} <工作区内的任务Markdown文件>"
    print -u2 "  ${WORKFLOW_SCRIPT} --run-dir <工作区.ai-collab内的新目录> <任务Markdown文件>"
    print -u2 "  ${WORKFLOW_SCRIPT} --resume <已有运行目录>"
    print -u2 "  ${WORKFLOW_SCRIPT} --rebaseline <已有运行目录>"
}

function fail() {
    print -u2 "错误: $1"
    exit "${2:-4}"
}

function is_non_negative_integer() {
    [[ "$1" =~ '^[0-9]+$' ]]
}

function read_state_value() {
    local state_file="$1"
    local key="$2"
    awk -v key="${key}" 'index($0, key "=") == 1 { print substr($0, length(key) + 2); exit }' "${state_file}" 2>/dev/null
}

function resolve_under_workspace() {
    local input_path="$1"
    local resolved_path
    resolved_path="${input_path:A}"
    if [[ "${resolved_path}" != "${WORKFLOW_ROOT}/"* ]]; then
        return 1
    fi
    print -r -- "${resolved_path}"
}

function resolve_under_collab_root() {
    local input_path="$1"
    local resolved_path
    resolved_path="${input_path:A}"
    if [[ "${resolved_path}" != "${COLLAB_ROOT}/"* ]]; then
        return 1
    fi
    print -r -- "${resolved_path}"
}

function is_positive_integer() {
    is_non_negative_integer "$1" && (( $1 > 0 ))
}

function live_child_pid_for_run_dir() {
    local run_dir="$1"
    local child_file="${run_dir}/current-child.pid"
    local child_pid=""
    if [[ ! -f "${child_file}" ]]; then
        return 1
    fi
    child_pid="$(<"${child_file}")"
    if is_positive_integer "${child_pid}" && kill -0 "${child_pid}" 2>/dev/null; then
        print -r -- "${child_pid}"
        return 0
    fi
    command rm -f "${child_file}" "${run_dir}/current-child.kind"
    return 1
}

function assert_no_live_child_for_run_dir() {
    local run_dir="$1"
    local label="$2"
    local child_pid=""
    child_pid="$(live_child_pid_for_run_dir "${run_dir}")" || return 0
    fail "${label}仍有子进程运行（PID=${child_pid}），拒绝并发恢复。请等待它结束或先安全终止该进程" 4
}

function track_current_child() {
    local child_pid="$1"
    local child_kind="$2"
    local temp_file="${RUN_DIR}/current-child.pid.tmp.$$"
    CURRENT_CHILD_PID="${child_pid}"
    CURRENT_CHILD_KIND="${child_kind}"
    print -r -- "${child_pid}" > "${temp_file}" || fail "无法记录当前子进程 PID" 4
    mv -f "${temp_file}" "${RUN_DIR}/current-child.pid" || fail "无法更新当前子进程 PID" 4
    print -r -- "${child_kind}" > "${RUN_DIR}/current-child.kind" || fail "无法记录当前子进程类型" 4
}

function clear_current_child() {
    local expected_pid="$1"
    local recorded_pid=""
    if [[ -f "${RUN_DIR}/current-child.pid" ]]; then
        recorded_pid="$(<"${RUN_DIR}/current-child.pid")"
    fi
    if [[ -z "${recorded_pid}" || "${recorded_pid}" == "${expected_pid}" ]]; then
        command rm -f "${RUN_DIR}/current-child.pid" "${RUN_DIR}/current-child.kind"
    fi
    if (( CURRENT_CHILD_PID == expected_pid )); then
        CURRENT_CHILD_PID=0
        CURRENT_CHILD_KIND=""
    fi
}

function wait_current_child() {
    local child_pid="$1"
    typeset -i child_exit=0
    wait "${child_pid}" || child_exit=$?
    clear_current_child "${child_pid}"
    return "${child_exit}"
}

function terminate_current_child() {
    local child_pid="${CURRENT_CHILD_PID}"
    local attempt
    if (( child_pid <= 0 )) && [[ -n "${RUN_DIR}" && -f "${RUN_DIR}/current-child.pid" ]]; then
        child_pid="$(<"${RUN_DIR}/current-child.pid")"
    fi
    if ! is_positive_integer "${child_pid}"; then
        return 0
    fi
    if kill -0 "${child_pid}" 2>/dev/null; then
        print -u2 "[workflow] 正在终止 ${CURRENT_CHILD_KIND:-子进程}（PID=${child_pid}）"
        kill -TERM "${child_pid}" 2>/dev/null || true
        for attempt in {1..20}; do
            if ! kill -0 "${child_pid}" 2>/dev/null; then
                break
            fi
            sleep 0.1
        done
        if kill -0 "${child_pid}" 2>/dev/null; then
            print -u2 "[workflow] 子进程未在 2 秒内退出，发送 KILL（PID=${child_pid}）"
            kill -KILL "${child_pid}" 2>/dev/null || true
        fi
        wait "${child_pid}" 2>/dev/null || true
    fi
    if ! kill -0 "${child_pid}" 2>/dev/null; then
        clear_current_child "${child_pid}"
    fi
}

function release_lock_dir() {
    local lock_dir="$1"
    local owner_pid=""
    if [[ ! -d "${lock_dir}" ]]; then
        return 0
    fi
    if [[ -f "${lock_dir}/pid" ]]; then
        owner_pid="$(<"${lock_dir}/pid")"
    fi
    if [[ "${owner_pid}" != "$$" ]]; then
        return 0
    fi
    if live_child_pid_for_run_dir "${RUN_DIR}" >/dev/null; then
        print -u2 "[workflow] 子进程仍存活，保留工作区锁: ${lock_dir}"
        return 0
    fi
    command rm -f "${lock_dir}/pid" "${lock_dir}/run-dir"
    rmdir "${lock_dir}" 2>/dev/null || true
}

function clear_stale_lock_dir() {
    local lock_dir="$1"
    local label="$2"
    local owner_pid=""
    local owner_run_dir=""
    if [[ ! -d "${lock_dir}" ]]; then
        return 0
    fi
    if [[ -f "${lock_dir}/pid" ]]; then
        owner_pid="$(<"${lock_dir}/pid")"
    fi
    if is_non_negative_integer "${owner_pid}" && kill -0 "${owner_pid}" 2>/dev/null; then
        fail "${label}正在运行（PID=${owner_pid}），不能并发修改同一工作区" 4
    fi
    if [[ -f "${lock_dir}/run-dir" ]]; then
        owner_run_dir="$(<"${lock_dir}/run-dir")"
    fi
    if [[ -n "${owner_run_dir}" && "${owner_run_dir}" == "${COLLAB_ROOT}/"* ]]; then
        assert_no_live_child_for_run_dir "${owner_run_dir}" "${label}的原执行阶段"
    fi
    command rm -f "${lock_dir}/pid" "${lock_dir}/run-dir"
    if ! rmdir "${lock_dir}" 2>/dev/null; then
        fail "${label}锁目录异常且无法安全清理: ${lock_dir}" 4
    fi
}

function validate_sequence_lock() {
    local owner_pid=""
    if [[ ! -d "${SEQUENCE_LOCK_DIR}" ]]; then
        return 0
    fi
    if [[ -f "${SEQUENCE_LOCK_DIR}/pid" ]]; then
        owner_pid="$(<"${SEQUENCE_LOCK_DIR}/pid")"
    fi
    if ! is_non_negative_integer "${owner_pid}" || ! kill -0 "${owner_pid}" 2>/dev/null; then
        clear_stale_lock_dir "${SEQUENCE_LOCK_DIR}" "顺序总控流程"
        return 0
    fi
    if [[ "${COLLAB_SEQUENCE_OWNER_PID:-}" == "${owner_pid}" ]]; then
        return 0
    fi
    fail "顺序总控流程正在运行（PID=${owner_pid}），不能并发启动单模块流程" 4
}

function acquire_workflow_lock() {
    mkdir -p "${COLLAB_ROOT}" || fail "无法创建协作目录: ${COLLAB_ROOT}"
    validate_sequence_lock
    clear_stale_lock_dir "${WORKFLOW_LOCK_DIR}" "单模块协作流程"
    if ! mkdir "${WORKFLOW_LOCK_DIR}"; then
        fail "无法获取单模块协作锁: ${WORKFLOW_LOCK_DIR}" 4
    fi
    LOCK_ACQUIRED=1
    print -r -- "$$" > "${WORKFLOW_LOCK_DIR}/pid"
    print -r -- "${RUN_DIR}" > "${WORKFLOW_LOCK_DIR}/run-dir"
}

function write_status() {
    local workflow_status="$1"
    local round="$2"
    local temp_file="${RUN_DIR}/workflow-status.txt.tmp.$$"
    {
        print -r -- "status=${workflow_status}"
        print -r -- "round=${round}"
        print -r -- "phase=${CURRENT_PHASE}"
        print -r -- "task=${TASK_FILE_ABS}"
        print -r -- "runDir=${RUN_DIR}"
        print -r -- "finishedAt=$(date '+%Y-%m-%d %H:%M:%S %z')"
    } > "${temp_file}" || return 1
    mv -f "${temp_file}" "${RUN_DIR}/workflow-status.txt"
}

function write_checkpoint() {
    local temp_file="${RUN_DIR}/workflow-checkpoint.txt.tmp.$$"
    {
        print -r -- "phase=${CURRENT_PHASE}"
        print -r -- "round=${CURRENT_ROUND}"
        print -r -- "latestSummary=${LATEST_SUMMARY}"
        print -r -- "buildLog=${BUILD_LOG}"
        print -r -- "buildSummary=${BUILD_SUMMARY}"
        print -r -- "reviewFile=${REVIEW_FILE}"
        print -r -- "buildExit=${BUILD_EXIT}"
        print -r -- "updatedAt=$(date '+%Y-%m-%d %H:%M:%S %z')"
    } > "${temp_file}" || fail "无法写入断点文件: ${temp_file}" 4
    mv -f "${temp_file}" "${RUN_DIR}/workflow-checkpoint.txt" \
        || fail "无法更新断点文件: ${RUN_DIR}/workflow-checkpoint.txt" 4
}

function handle_signal() {
    local signal_name="$1"
    local exit_code="$2"
    trap - HUP INT TERM
    terminate_current_child
    if [[ -n "${RUN_DIR}" && -d "${RUN_DIR}" ]]; then
        write_status "INTERRUPTED_${signal_name}" "${CURRENT_ROUND}" || true
    fi
    print -u2 "[workflow] 收到 ${signal_name}，已保留断点。恢复命令：${WORKFLOW_SCRIPT} --resume ${RUN_DIR}"
    exit "${exit_code}"
}

function cleanup() {
    if (( LOCK_ACQUIRED == 1 )); then
        release_lock_dir "${WORKFLOW_LOCK_DIR}"
    fi
}

trap 'handle_signal HUP 129' HUP
trap 'handle_signal INT 130' INT
trap 'handle_signal TERM 143' TERM
trap 'cleanup' EXIT

# 锁屏不会影响流程；自动用 caffeinate 防止空闲睡眠。合盖、低电量或强制睡眠仍会暂停。
if [[ "${COLLAB_CAFFEINATED:-0}" != "1" \
        && "${COLLAB_DISABLE_CAFFEINATE:-0}" != "1" \
        && -x /usr/bin/caffeinate ]]; then
    export COLLAB_CAFFEINATED=1
    exec /usr/bin/caffeinate -i "${WORKFLOW_SCRIPT}" "$@"
    fail "无法通过 caffeinate 启动流程" 4
fi

if ! is_non_negative_integer "${MAX_FIX_ROUNDS_VALUE}"; then
    fail "MAX_FIX_ROUNDS 必须是非负整数"
fi
if [[ "${REQUIRE_TESTS_VALUE}" != "0" && "${REQUIRE_TESTS_VALUE}" != "1" ]]; then
    fail "REQUIRE_TESTS 只能是 0 或 1"
fi
if ! command -v codex >/dev/null 2>&1; then
    fail "未找到 codex CLI"
fi
if ! command -v claude >/dev/null 2>&1; then
    fail "未找到 claude CLI"
fi
if ! command -v mvn >/dev/null 2>&1; then
    fail "未找到 Maven"
fi
if ! command -v git >/dev/null 2>&1; then
    fail "未找到 Git"
fi
if [[ ! -x "${COLLAB_JAVA_HOME_VALUE}/bin/java" ]]; then
    fail "未找到可执行的 JDK 8: ${COLLAB_JAVA_HOME_VALUE}/bin/java"
fi

readonly COLLAB_JAVA_VERSION_LINE="$("${COLLAB_JAVA_HOME_VALUE}/bin/java" -version 2>&1 | head -n 1)"
if [[ "${COLLAB_JAVA_VERSION_LINE}" != *'"1.8'* ]]; then
    fail "COLLAB_JAVA_HOME 必须指向 JDK 8，实际是: ${COLLAB_JAVA_VERSION_LINE}"
fi

MODE="NEW"
REQUESTED_RUN_DIR=""
if (( $# == 2 )) && [[ "$1" == "--resume" ]]; then
    MODE="RESUME"
    REQUESTED_RUN_DIR="$2"
elif (( $# == 2 )) && [[ "$1" == "--rebaseline" ]]; then
    MODE="RESUME"
    REBASELINE_REQUESTED=1
    REQUESTED_RUN_DIR="$2"
elif (( $# == 3 )) && [[ "$1" == "--run-dir" ]]; then
    REQUESTED_RUN_DIR="$2"
    TASK_FILE_ABS="$(resolve_under_workspace "$3")" || fail "任务文件必须位于工作区内: ${WORKFLOW_ROOT}"
elif (( $# == 1 )); then
    TASK_FILE_ABS="$(resolve_under_workspace "$1")" || fail "任务文件必须位于工作区内: ${WORKFLOW_ROOT}"
else
    usage
    exit 4
fi

if [[ "${MODE}" == "RESUME" ]]; then
    RUN_DIR="$(resolve_under_collab_root "${REQUESTED_RUN_DIR}")" \
        || fail "恢复目录必须位于 ${COLLAB_ROOT} 内"
    if [[ ! -d "${RUN_DIR}" ]]; then
        fail "恢复目录不存在: ${RUN_DIR}"
    fi
    if [[ ! -f "${RUN_DIR}/00-task-path.txt" ]]; then
        fail "恢复目录缺少任务路径: ${RUN_DIR}"
    fi
    TASK_FILE_ABS="$(<"${RUN_DIR}/00-task-path.txt")"
    TASK_FILE_ABS="$(resolve_under_workspace "${TASK_FILE_ABS}")" \
        || fail "断点中的任务文件已不在工作区内"
else
    if [[ ! -f "${TASK_FILE_ABS}" ]]; then
        fail "任务文件不存在: ${TASK_FILE_ABS}"
    fi
    if [[ "${TASK_FILE_ABS}" != *.md ]]; then
        fail "任务文件必须是 Markdown 文件: ${TASK_FILE_ABS}"
    fi
    mkdir -p "${COLLAB_ROOT}" || fail "无法创建协作目录: ${COLLAB_ROOT}"
    if [[ -n "${REQUESTED_RUN_DIR}" ]]; then
        RUN_DIR="$(resolve_under_collab_root "${REQUESTED_RUN_DIR}")" \
            || fail "运行目录必须位于 ${COLLAB_ROOT} 内"
        mkdir -p "${RUN_DIR:h}" || fail "无法创建运行目录父目录: ${RUN_DIR:h}"
        if ! mkdir "${RUN_DIR}"; then
            fail "运行目录已存在或无法创建: ${RUN_DIR}"
        fi
    else
        readonly RUN_ID="$(date '+%Y%m%d-%H%M%S')-$$"
        RUN_DIR="${COLLAB_ROOT}/${RUN_ID}"
        if ! mkdir "${RUN_DIR}"; then
            fail "无法创建运行目录: ${RUN_DIR}"
        fi
    fi
    print -r -- "${TASK_FILE_ABS}" > "${RUN_DIR}/00-task-path.txt" \
        || fail "无法保存任务路径" 4
fi

if [[ ! -f "${TASK_FILE_ABS}" || "${TASK_FILE_ABS}" != *.md ]]; then
    fail "任务文件不存在或不是 Markdown: ${TASK_FILE_ABS}"
fi

assert_no_live_child_for_run_dir "${RUN_DIR}" "该运行目录"
acquire_workflow_lock

if ! git -C "${WORKFLOW_ROOT}" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    fail "工作区不是 Git 仓库，无法建立用户改动保护基线" 4
fi
if ! git -C "${WORKFLOW_ROOT}" rev-parse --verify HEAD >/dev/null 2>&1; then
    fail "Git 仓库没有 HEAD 提交，无法建立用户改动保护基线" 4
fi

function capture_workspace_snapshot() {
    local prefix="$1"
    local status_file="${RUN_DIR}/${prefix}-git-status.txt"
    local status_error_file="${RUN_DIR}/${prefix}-git-status.stderr.log"
    local diff_file="${RUN_DIR}/${prefix}-git-diff.patch"
    local diff_error_file="${RUN_DIR}/${prefix}-git-diff.stderr.log"

    git -C "${WORKFLOW_ROOT}" -c core.quotepath=false \
        status --porcelain=v1 --untracked-files=all \
        > "${status_file}" 2> "${status_error_file}" \
        || fail "无法生成 Git 状态快照，Git 错误见 ${status_error_file}" 4

    git -C "${WORKFLOW_ROOT}" -c core.quotepath=false diff --binary "${ORIGINAL_HEAD_SHA}" \
        > "${diff_file}" 2> "${diff_error_file}" \
        || fail "无法生成 Git 补丁快照，Git 错误见 ${diff_error_file}" 4
}

function unique_artifact_prefix() {
    local label="$1"
    local timestamp
    local candidate
    typeset -i sequence=0
    timestamp="$(date '+%Y%m%d-%H%M%S')"
    candidate="${label}-${timestamp}-$$"
    while [[ -e "${RUN_DIR}/${candidate}-git-status.txt" \
            || -e "${RUN_DIR}/${candidate}-files" ]]; do
        (( sequence++ ))
        candidate="${label}-${timestamp}-$$-${sequence}"
    done
    print -r -- "${candidate}"
}

function capture_file_backup() {
    local prefix="$1"
    local description="$2"
    local path_list="${RUN_DIR}/${prefix}-paths.nul"
    local manifest_file="${RUN_DIR}/${prefix}-backup-manifest.txt"
    local backup_root="${RUN_DIR}/${prefix}-files"
    local relative_path
    local source_path
    local target_path

    if [[ -e "${backup_root}" || -e "${manifest_file}" || -e "${path_list}" ]]; then
        fail "${description}备份前缀已存在，拒绝覆盖: ${prefix}" 4
    fi
    : > "${path_list}"
    : > "${manifest_file}"
    mkdir -p "${backup_root}" || fail "无法创建${description}备份目录" 4

    git -C "${WORKFLOW_ROOT}" diff --name-only --diff-filter=ACMRTUXB -z HEAD \
        >> "${path_list}" || fail "无法枚举已修改 tracked 文件" 4
    git -C "${WORKFLOW_ROOT}" ls-files --others --exclude-standard -z \
        >> "${path_list}" || fail "无法枚举未跟踪文件" 4

    while IFS= read -r -d '' relative_path; do
        if [[ -z "${relative_path}" || "${relative_path}" == /* || "${relative_path}" == ../* ]]; then
            continue
        fi
        source_path="${WORKFLOW_ROOT}/${relative_path}"
        target_path="${backup_root}/${relative_path}"
        if [[ -f "${source_path}" || -L "${source_path}" ]]; then
            mkdir -p "${target_path:h}" || fail "无法创建${description}备份父目录" 4
            cp -pP "${source_path}" "${target_path}" \
                || fail "无法备份${description}文件: ${relative_path}" 4
            print -r -- "${relative_path}" >> "${manifest_file}"
        fi
    done < "${path_list}"
}

function capture_baseline_backup() {
    capture_file_backup "00-baseline" "首次运行基线"
}

function capture_resume_backup() {
    RESUME_BACKUP_PREFIX="$(unique_artifact_prefix "resume")"
    capture_workspace_snapshot "${RESUME_BACKUP_PREFIX}"
    capture_file_backup "${RESUME_BACKUP_PREFIX}" "恢复前工作区"
    RESUME_BACKUP_MANIFEST="${RUN_DIR}/${RESUME_BACKUP_PREFIX}-backup-manifest.txt"
    RESUME_BACKUP_ROOT="${RUN_DIR}/${RESUME_BACKUP_PREFIX}-files"
    printf '%s\t%s\t%s\t%s\t%s\n' "$(date '+%Y-%m-%d %H:%M:%S %z')" \
        "${RESUME_BACKUP_PREFIX}" \
        "${RUN_DIR}/${RESUME_BACKUP_PREFIX}-git-diff.patch" \
        "${RESUME_BACKUP_MANIFEST}" "${RESUME_BACKUP_ROOT}" \
        >> "${RUN_DIR}/resume-backups.tsv"
}

function load_review_baseline_references() {
    local saved_status=""
    local saved_diff=""
    REVIEW_BASELINE_STATUS="${RUN_DIR}/00-baseline-git-status.txt"
    REVIEW_BASELINE_DIFF="${RUN_DIR}/00-baseline-git-diff.patch"
    if [[ -f "${RUN_DIR}/review-baseline-status-path.txt" \
            && -f "${RUN_DIR}/review-baseline-diff-path.txt" ]]; then
        saved_status="$(<"${RUN_DIR}/review-baseline-status-path.txt")"
        saved_diff="$(<"${RUN_DIR}/review-baseline-diff-path.txt")"
        if [[ "${saved_status:A}" == "${RUN_DIR}/"* && -f "${saved_status}" \
                && "${saved_diff:A}" == "${RUN_DIR}/"* && -f "${saved_diff}" ]]; then
            REVIEW_BASELINE_STATUS="${saved_status}"
            REVIEW_BASELINE_DIFF="${saved_diff}"
        else
            fail "评审比较基线引用损坏或越界，请检查运行目录中的 review-baseline-*-path.txt" 4
        fi
    fi
}

function persist_review_baseline_references() {
    local status_path="$1"
    local diff_path="$2"
    print -r -- "${status_path}" > "${RUN_DIR}/review-baseline-status-path.txt"
    print -r -- "${diff_path}" > "${RUN_DIR}/review-baseline-diff-path.txt"
    REVIEW_BASELINE_STATUS="${status_path}"
    REVIEW_BASELINE_DIFF="${diff_path}"
}

function latest_completed_workspace_patch() {
    local -a patches
    patches=("${RUN_DIR}"/*-workspace-r*-git-diff.patch(N))
    if (( ${#patches[@]} == 0 )); then
        print -r -- "无（当前没有可证明归属的上一轮完整工作区补丁）"
        return 0
    fi
    print -r -- "${patches[-1]}"
}

function current_head_sha() {
    git -C "${WORKFLOW_ROOT}" rev-parse HEAD 2>/dev/null || print -r -- "NO_GIT"
}

function assert_head_unchanged() {
    local phase="$1"
    local now_sha
    now_sha="$(current_head_sha)"
    if [[ "${now_sha}" != "${BASELINE_HEAD_SHA}" ]]; then
        write_status "UNEXPECTED_COMMIT" "${CURRENT_ROUND}" || true
        fail "${phase} 阶段 git HEAD 发生变化（${BASELINE_HEAD_SHA} -> ${now_sha}）。确认该提交应纳入当前运行后，可执行：${WORKFLOW_SCRIPT} --rebaseline ${RUN_DIR}；否则请新建运行。禁止手工改基线文件" 4
    fi
}

function summarize_build() {
    local log_file="$1"
    local exit_code="$2"
    local summary_file="$3"
    local tests_lines
    local tests_total
    tests_lines="$(grep -E 'Tests run:[[:space:]]*[0-9]+' "${log_file}" 2>/dev/null | tail -n 20 || true)"
    tests_total="$(print -r -- "${tests_lines}" | awk '
        match($0, /Tests run:[[:space:]]*[0-9]+/) {
            value = substr($0, RSTART, RLENGTH)
            sub(/Tests run:[[:space:]]*/, "", value)
            total += value + 0
        }
        END { print total + 0 }
    ')"
    {
        print -r -- "exitCode=${exit_code}"
        print -r -- "testsRunTotal=${tests_total}"
        if (( tests_total > 0 )); then
            print -r -- "testsExecuted=YES"
        else
            print -r -- "testsExecuted=NO"
            print -r -- ""
            print -r -- "警告：本次构建执行的测试总数为 0。"
            if [[ "${REQUIRE_TESTS_VALUE}" == "1" ]]; then
                print -r -- "当前 REQUIRE_TESTS=1，自动门禁禁止 PASS。"
            fi
        fi
        print -r -- ""
        print -r -- "--- Maven 测试摘要（最多 20 条）---"
        print -r -- "${tests_lines}"
        print -r -- ""
        print -r -- "--- 构建日志中的 ERROR 行（最多 40 条）---"
        grep -E '\[ERROR\]' "${log_file}" 2>/dev/null | head -n 40 || true
    } > "${summary_file}"
}

function build_gate_passes() {
    if (( BUILD_EXIT != 0 )); then
        return 1
    fi
    if [[ "${REQUIRE_TESTS_VALUE}" == "0" ]]; then
        return 0
    fi
    [[ "$(read_state_value "${BUILD_SUMMARY}" "testsExecuted")" == "YES" ]]
}

function run_java8_build() {
    local round="$1"
    local log_file="$2"
    local child_pid
    print "[workflow] 执行 Java 8 Maven verify，round=${round}"
    (
        export JAVA_HOME="${COLLAB_JAVA_HOME_VALUE}"
        export PATH="${COLLAB_JAVA_HOME_VALUE}/bin:${PATH}"
        cd "${WORKFLOW_ROOT}" || exit 1
        exec mvn clean verify
    ) > "${log_file}" 2>&1 &
    child_pid=$!
    track_current_child "${child_pid}" "maven"
    wait_current_child "${child_pid}"
}

function run_codex_implementation() {
    local output_file="$1"
    local cli_log="$2"
    local prompt
    local child_pid
    local completed_workspace_patch
    completed_workspace_patch="$(latest_completed_workspace_patch)"
    prompt="你是本任务的实现者。请在工作区 ${WORKFLOW_ROOT} 内完成任务文件 ${TASK_FILE_ABS} 的全部开发工作。

开始前必须完整阅读 docs/Claude-Code与Codex自动协作流程.md、AGENTS.md、任务文件、AI体检报告分析-开发方案V1.md、AI体检报告分析-精简设计方案V1.md、体检报告分析需求.md，以及受影响的 Prompt、Schema 和 Java 常量。严格遵守 Java 8 / Spring Boot 2.7 / javax、三层职责、数据安全和零重试约束。

用户改动保护资料：
- 首次运行前清单：${RUN_DIR}/00-baseline-backup-manifest.txt
- 首次运行前备份：${RUN_DIR}/00-baseline-files
- 所有恢复快照索引（存在时）：${RUN_DIR}/resume-backups.tsv
- 本次恢复前清单：${RESUME_BACKUP_MANIFEST:-无（本次不是恢复执行）}
- 本次恢复前备份：${RESUME_BACKUP_ROOT:-无（本次不是恢复执行）}
- 上一轮已完成的工作区累计补丁：${completed_workspace_patch}

安全边界：首次基线中的改动一律视为用户改动。恢复时，只有通过对比首次基线补丁与“上一轮已完成的工作区累计补丁”能够明确证明由自动流程产生的具体改动，才可以继续修改或整理。未出现在该已完成补丁中、在中断期间新增或变化、没有完整补丁、或归属无法确认的任何当前改动，一律按用户改动处理，不得覆盖、删除、回滚或顺手整理。恢复备份只是意外恢复安全网，不构成覆盖授权。开工前先看 git status 和上述资料。.ai-collab 是自动流程产物目录，不得修改。

要求：
1. 实际修改代码并补充测试，不要只给方案；
2. 保留用户已有改动，不做 commit/push，不修改任何 Git 引用；
3. 不调用 Claude Code 代写代码；
4. 不记录或输出姓名、报告原文、OCR 文本、健康数据或完整模型请求响应；
5. 如果任务依赖未拍板的产品/医疗行为或与高优先级文档冲突，停止修改相关部分并在最终结果明确写 BLOCKED；
6. 最终列出改动文件、测试命令与结果、剩余问题。"

    # approval=never + workspace-write：无人值守时不允许升级权限或外部副作用。
    codex -a never exec \
        -C "${WORKFLOW_ROOT}" \
        --sandbox workspace-write \
        --ephemeral \
        --output-last-message "${output_file}" \
        "${prompt}" > "${cli_log}" 2>&1 &
    child_pid=$!
    track_current_child "${child_pid}" "codex-implementation"
    wait_current_child "${child_pid}"
}

function run_claude_review() {
    local round="$1"
    local build_log="$2"
    local build_summary="$3"
    local implementation_summary="$4"
    local baseline_status="$5"
    local baseline_diff="$6"
    local baseline_backup_manifest="$7"
    local baseline_backup_root="$8"
    local round_status="$9"
    local round_diff="${10}"
    local output_file="${11}"
    local cli_log="${12}"
    local prompt
    local child_pid
    prompt="你是本任务的独立代码评审者，只评审，不修改任何文件。

工作区：${WORKFLOW_ROOT}（已是你的当前目录）
任务文件：${TASK_FILE_ABS}
本轮：${round}

先用 Read 工具逐个读完下面这些文件，再开始评审：
- 协作流程与判定规则：${WORKFLOW_ROOT}/docs/Claude-Code与Codex自动协作流程.md
- 任务文件：${TASK_FILE_ABS}
- 当前评审比较基线的工作区状态：${baseline_status}
- 当前评审比较基线的累计补丁：${baseline_diff}
- 首次运行前的可恢复文件清单：${baseline_backup_manifest}
- 首次运行前的可恢复文件备份根目录（需要对比时读取对应文件）：${baseline_backup_root}
- 各次恢复前快照与逐文件备份索引（存在时）：${RUN_DIR}/resume-backups.tsv
- 本轮构建之后的工作区状态：${round_status}
- 本轮构建之后相对首次运行原始提交（00-original-head.txt；历史改写导致该提交不可用时，相对显式重建后的新基线）的累计补丁：${round_diff}
- 构建结论摘要：${build_summary}
- Java 8 Maven verify 完整日志：${build_log}
- Codex 本轮自述：${implementation_summary}

然后完整阅读 AGENTS.md、任务文件、AI体检报告分析-开发方案V1.md、AI体检报告分析-精简设计方案V1.md、体检报告分析需求.md，以及所有受影响代码、测试、Prompt、Schema、DDL 和 Java 常量。根据功能和正式开发文档独立核查，不以 Codex 的自述作为完成证据。

评审边界：
- 只评审任务文件划定的范围。任务范围明确写不做的内容，不得作为 P0/P1 提出；
- 任务范围本身有问题时写成 P2 备注，不影响 VERDICT；
- Codex 自称 BLOCKED 时要独立判断，不照单全收；
- 对比首次基线、当前评审比较基线、所有恢复前备份和本轮工作区，确认没有覆盖或删除首次运行前或中断期间的用户改动，有则是 P0。

第一行必须严格输出以下三者之一，前面不要有任何铺垫文字：
VERDICT: PASS
VERDICT: CHANGES_REQUIRED
VERDICT: BLOCKED

PASS 仅在没有 P0/P1、功能完成、构建通过且测试门禁通过时使用。构建摘要 testsExecuted=NO 且 REQUIRE_TESTS=1 时一律不得 PASS。每条问题必须包含优先级、文件与行号、问题、后果、明确修复要求和建议回归测试。若需要产品、医疗、外部接口或权限决定，输出 BLOCKED。

禁止输出姓名、报告原文、OCR 文本、健康数据或完整模型请求响应。"

    (
        cd "${WORKFLOW_ROOT}" || exit 1
        exec claude \
            --print \
            --safe-mode \
            --permission-mode dontAsk \
            --tools "Read,Glob,Grep" \
            --no-session-persistence \
            --output-format text \
            "${prompt}"
    ) > "${output_file}" 2> "${cli_log}" &
    child_pid=$!
    track_current_child "${child_pid}" "claude-review"
    wait_current_child "${child_pid}"
}

function run_codex_fix() {
    local round="$1"
    local review_file="$2"
    local build_log="$3"
    local build_summary="$4"
    local output_file="$5"
    local cli_log="$6"
    local prompt
    local child_pid
    local completed_workspace_patch
    completed_workspace_patch="$(latest_completed_workspace_patch)"
    prompt="你是本任务的修复者。Claude Code 已完成第 $((round - 1)) 轮独立评审。

评审文件：${review_file}
构建结论摘要：${build_summary}
构建完整日志：${build_log}
原任务文件：${TASK_FILE_ABS}
用户原有改动的可恢复备份：${RUN_DIR}/00-baseline-files
用户原有改动的首次清单：${RUN_DIR}/00-baseline-backup-manifest.txt
所有恢复快照索引（存在时）：${RUN_DIR}/resume-backups.tsv
本次恢复前清单：${RESUME_BACKUP_MANIFEST:-无（本次不是恢复执行）}
本次恢复前备份：${RESUME_BACKUP_ROOT:-无（本次不是恢复执行）}
上一轮已完成的工作区累计补丁：${completed_workspace_patch}

恢复安全边界：只有通过对比首次基线补丁与上述已完成工作区补丁，能够明确证明由自动流程产生的具体改动，才可继续修改或整理。未出现在该已完成补丁中、在中断期间新增或变化、没有完整补丁、或归属无法确认的任何当前改动，一律按用户改动处理，不得覆盖、删除、回滚或顺手整理。恢复备份只是意外恢复安全网，不构成覆盖授权。

必须重新阅读 docs/Claude-Code与Codex自动协作流程.md、AGENTS.md、任务文件和受影响的正式开发文档，逐条核实并处理评审中的 P0/P1：
1. 同意的意见必须修改代码并补回归测试；
2. 不同意时必须用正式文档、代码和测试证据说明，不能忽略；
3. 不得删除测试、放宽断言、吞异常、写假数据或改成 TODO 来规避问题；
4. 涉及产品/医疗决策或任务授权外扩时停止并报告 BLOCKED；
5. 不调用 Claude Code 参与修复，不 commit/push，不修改 Git 引用；
6. 不覆盖、删除或回滚用户首次运行前或中断期间产生的改动；
7. 不记录或输出姓名、报告原文、OCR 文本、健康数据或完整模型请求响应；
8. REQUIRE_TESTS=1 且构建摘要 testsExecuted=NO 时，必须补上覆盖本轮交付的测试；
9. 最终逐条说明评审意见的处理结果，并列出改动文件和测试。"

    codex -a never exec \
        -C "${WORKFLOW_ROOT}" \
        --sandbox workspace-write \
        --ephemeral \
        --output-last-message "${output_file}" \
        "${prompt}" > "${cli_log}" 2>&1 &
    child_pid=$!
    track_current_child "${child_pid}" "codex-fix"
    wait_current_child "${child_pid}"
}

function parse_verdict() {
    local review_file="$1"
    grep -v '^[[:space:]]*$' "${review_file}" 2>/dev/null \
        | head -n 5 \
        | grep -m 1 -E '^VERDICT: (PASS|CHANGES_REQUIRED|BLOCKED)$' || true
}

function load_checkpoint() {
    local checkpoint_file="${RUN_DIR}/workflow-checkpoint.txt"
    CURRENT_PHASE="$(read_state_value "${checkpoint_file}" "phase")"
    CURRENT_ROUND="$(read_state_value "${checkpoint_file}" "round")"
    LATEST_SUMMARY="$(read_state_value "${checkpoint_file}" "latestSummary")"
    BUILD_LOG="$(read_state_value "${checkpoint_file}" "buildLog")"
    BUILD_SUMMARY="$(read_state_value "${checkpoint_file}" "buildSummary")"
    REVIEW_FILE="$(read_state_value "${checkpoint_file}" "reviewFile")"
    BUILD_EXIT="$(read_state_value "${checkpoint_file}" "buildExit")"
    if [[ -z "${CURRENT_PHASE}" ]] || ! is_non_negative_integer "${CURRENT_ROUND}" \
            || ! is_non_negative_integer "${BUILD_EXIT}"; then
        fail "断点文件损坏: ${checkpoint_file}" 4
    fi
}

load_review_baseline_references

if [[ "${MODE}" == "NEW" ]]; then
    BASELINE_HEAD_SHA="$(current_head_sha)"
    ORIGINAL_HEAD_SHA="${BASELINE_HEAD_SHA}"
    print -r -- "${BASELINE_HEAD_SHA}" > "${RUN_DIR}/00-baseline-head.txt"
    print -r -- "${ORIGINAL_HEAD_SHA}" > "${RUN_DIR}/00-original-head.txt"
    LATEST_SUMMARY="${RUN_DIR}/01-codex-implementation.md"
    CURRENT_PHASE="BASELINE_RUNNING"
    CURRENT_ROUND=0
    BUILD_EXIT=0
    write_checkpoint
    write_status "RUNNING" 0 || fail "无法写入运行状态" 4
else
    if [[ ! -f "${RUN_DIR}/00-baseline-head.txt" ]]; then
        fail "恢复目录缺少 00-baseline-head.txt，拒绝静默采用当前 HEAD。请新建运行，或从可信记录恢复该文件" 4
    fi
    BASELINE_HEAD_SHA="$(<"${RUN_DIR}/00-baseline-head.txt")"
    if [[ -f "${RUN_DIR}/00-original-head.txt" ]]; then
        ORIGINAL_HEAD_SHA="$(<"${RUN_DIR}/00-original-head.txt")"
    else
        ORIGINAL_HEAD_SHA="${BASELINE_HEAD_SHA}"
        print -r -- "${ORIGINAL_HEAD_SHA}" > "${RUN_DIR}/00-original-head.txt"
    fi
    if (( REBASELINE_REQUESTED == 1 )); then
        previous_head_sha="${BASELINE_HEAD_SHA}"
        BASELINE_HEAD_SHA="$(current_head_sha)"
        print -r -- "${BASELINE_HEAD_SHA}" > "${RUN_DIR}/00-baseline-head.txt"
        rebaseline_snapshot_prefix="$(unique_artifact_prefix "rebaseline")"
        printf '%s\t%s\t%s\t%s\n' "$(date '+%Y-%m-%d %H:%M:%S %z')" \
            "${previous_head_sha}" "${BASELINE_HEAD_SHA}" "${rebaseline_snapshot_prefix}" \
            >> "${RUN_DIR}/rebaseline-history.tsv"
        original_head_check_error="${RUN_DIR}/${rebaseline_snapshot_prefix}-original-head-check.stderr.log"
        if ! git -C "${WORKFLOW_ROOT}" cat-file -e "${ORIGINAL_HEAD_SHA}^{commit}" \
                2> "${original_head_check_error}"; then
            previous_original_head_sha="${ORIGINAL_HEAD_SHA}"
            print -u2 "[workflow] 原始 HEAD ${ORIGINAL_HEAD_SHA} 已不可用（历史被改写或对象已清理），累计补丁基点重置为新基线 ${BASELINE_HEAD_SHA}；Git 原始错误见 ${original_head_check_error}"
            ORIGINAL_HEAD_SHA="${BASELINE_HEAD_SHA}"
            print -r -- "${ORIGINAL_HEAD_SHA}" > "${RUN_DIR}/00-original-head.txt"
            printf '%s\t%s\t%s\n' "$(date '+%Y-%m-%d %H:%M:%S %z')" \
                "${previous_original_head_sha}" "${ORIGINAL_HEAD_SHA}" \
                >> "${RUN_DIR}/original-head-reset-history.tsv"
        fi
        capture_workspace_snapshot "${rebaseline_snapshot_prefix}"
        persist_review_baseline_references \
            "${RUN_DIR}/${rebaseline_snapshot_prefix}-git-status.txt" \
            "${RUN_DIR}/${rebaseline_snapshot_prefix}-git-diff.patch"
        print "[workflow] 已显式接受 HEAD 变化：${previous_head_sha} -> ${BASELINE_HEAD_SHA}"
    fi
    assert_head_unchanged "恢复校验"
    previous_status=""
    if [[ -f "${RUN_DIR}/workflow-status.txt" ]]; then
        previous_status="$(read_state_value "${RUN_DIR}/workflow-status.txt" "status")"
    fi
    if [[ "${previous_status}" == "PASS" && "${REBASELINE_REQUESTED}" != "1" ]]; then
        print "[workflow] 该运行已完成：${RUN_DIR}"
        exit 0
    fi
    if [[ -f "${RUN_DIR}/workflow-checkpoint.txt" ]]; then
        load_checkpoint
    else
        LATEST_SUMMARY="${RUN_DIR}/01-codex-implementation.md"
        CURRENT_PHASE="BASELINE_RUNNING"
        CURRENT_ROUND=0
        BUILD_EXIT=0
        write_checkpoint
    fi
    # 显式重建基线后，旧构建/评审都不再代表当前 HEAD；被中断的 Codex 阶段则仍重跑该阶段。
    if (( REBASELINE_REQUESTED == 1 )); then
        if [[ "${CURRENT_PHASE}" != "IMPLEMENTATION_RUNNING" \
                && "${CURRENT_PHASE}" != "FIX_RUNNING" ]]; then
            CURRENT_PHASE="BUILD_RUNNING"
            write_checkpoint
        fi
    # BLOCKED/达到轮次/工具失败后允许用户修正文档、环境或提高轮次再恢复，先重新构建和评审。
    elif [[ "${previous_status}" == "BLOCKED" \
            || "${previous_status}" == "MAX_FIX_ROUNDS_REACHED" \
            || "${previous_status}" == "CLAUDE_REVIEW_FAILED" ]]; then
        CURRENT_PHASE="BUILD_RUNNING"
        write_checkpoint
    fi
    # Codex/构建/评审恢复前先保存用户在中断窗口内产生的全部当前文件和补丁。
    # 每次使用独立前缀并追加索引，绝不覆盖首次基线或以前的恢复快照。
    capture_resume_backup
    write_status "RESUMED" "${CURRENT_ROUND}" || fail "无法写入恢复状态" 4
    print "[workflow] 从断点恢复：phase=${CURRENT_PHASE}, round=${CURRENT_ROUND}, runDir=${RUN_DIR}"
fi

if [[ "${CURRENT_PHASE}" == "BASELINE_RUNNING" ]]; then
    capture_workspace_snapshot "00-baseline"
    capture_baseline_backup
    CURRENT_PHASE="INIT"
    write_checkpoint
fi

if [[ "${CURRENT_PHASE}" == "INIT" || "${CURRENT_PHASE}" == "IMPLEMENTATION_RUNNING" ]]; then
    CURRENT_PHASE="IMPLEMENTATION_RUNNING"
    write_checkpoint
    print "[workflow] 开始 Codex 首次开发"
    if ! run_codex_implementation \
            "${LATEST_SUMMARY}" \
            "${RUN_DIR}/01-codex-implementation-cli.log"; then
        write_status "CODEX_IMPLEMENTATION_FAILED" 0 || true
        fail "Codex 首次开发调用失败，可从断点恢复。产物目录: ${RUN_DIR}" 4
    fi
    assert_head_unchanged "首次开发"
    CURRENT_PHASE="IMPLEMENTATION_DONE"
    write_checkpoint
fi

while true; do
    if [[ "${CURRENT_PHASE}" == "FIX_RUNNING" ]]; then
        print "[workflow] 继续 Codex 第 ${CURRENT_ROUND} 轮修复"
        if ! run_codex_fix \
                "${CURRENT_ROUND}" \
                "${REVIEW_FILE}" \
                "${BUILD_LOG}" \
                "${BUILD_SUMMARY}" \
                "${LATEST_SUMMARY}" \
                "${LATEST_SUMMARY%.md}-cli.log"; then
            write_status "CODEX_FIX_FAILED" "${CURRENT_ROUND}" || true
            fail "Codex 修复调用失败，可从同一轮断点恢复。产物目录: ${RUN_DIR}" 4
        fi
        assert_head_unchanged "第 ${CURRENT_ROUND} 轮修复"
        CURRENT_PHASE="FIX_DONE"
        write_checkpoint
    fi

    if [[ "${CURRENT_PHASE}" == "IMPLEMENTATION_DONE" \
            || "${CURRENT_PHASE}" == "FIX_DONE" \
            || "${CURRENT_PHASE}" == "BUILD_RUNNING" ]]; then
        printf -v build_prefix '%02d' "$((CURRENT_ROUND * 3 + 2))"
        printf -v review_prefix '%02d' "$((CURRENT_ROUND * 3 + 3))"
        BUILD_LOG="${RUN_DIR}/${build_prefix}-build-r${CURRENT_ROUND}.log"
        BUILD_SUMMARY="${RUN_DIR}/${build_prefix}-build-summary-r${CURRENT_ROUND}.txt"
        REVIEW_FILE="${RUN_DIR}/${review_prefix}-claude-review-r${CURRENT_ROUND}.md"
        CURRENT_PHASE="BUILD_RUNNING"
        write_checkpoint

        BUILD_EXIT=0
        run_java8_build "${CURRENT_ROUND}" "${BUILD_LOG}" || BUILD_EXIT=$?
        summarize_build "${BUILD_LOG}" "${BUILD_EXIT}" "${BUILD_SUMMARY}"

        # 评审快照必须在构建后生成，保证补丁与 Claude 实际看到的工作区一致。
        capture_workspace_snapshot "${build_prefix}-workspace-r${CURRENT_ROUND}"
        CURRENT_PHASE="BUILD_DONE"
        write_checkpoint
    fi

    if [[ "${CURRENT_PHASE}" == "BUILD_DONE" || "${CURRENT_PHASE}" == "REVIEW_RUNNING" ]]; then
        printf -v build_prefix '%02d' "$((CURRENT_ROUND * 3 + 2))"
        CURRENT_PHASE="REVIEW_RUNNING"
        write_checkpoint
        print "[workflow] 开始 Claude Code 第 ${CURRENT_ROUND} 轮只读评审"
        if ! run_claude_review \
                "${CURRENT_ROUND}" \
                "${BUILD_LOG}" \
                "${BUILD_SUMMARY}" \
                "${LATEST_SUMMARY}" \
                "${REVIEW_BASELINE_STATUS}" \
                "${REVIEW_BASELINE_DIFF}" \
                "${RUN_DIR}/00-baseline-backup-manifest.txt" \
                "${RUN_DIR}/00-baseline-files" \
                "${RUN_DIR}/${build_prefix}-workspace-r${CURRENT_ROUND}-git-status.txt" \
                "${RUN_DIR}/${build_prefix}-workspace-r${CURRENT_ROUND}-git-diff.patch" \
                "${REVIEW_FILE}" \
                "${REVIEW_FILE%.md}-cli.log"; then
            write_status "CLAUDE_REVIEW_FAILED" "${CURRENT_ROUND}" || true
            fail "Claude Code 评审调用失败，可从断点恢复。产物目录: ${RUN_DIR}" 4
        fi
        CURRENT_PHASE="REVIEW_DONE"
        write_checkpoint
    fi

    if [[ "${CURRENT_PHASE}" != "REVIEW_DONE" ]]; then
        write_status "UNEXPECTED_TERMINATION" "${CURRENT_ROUND}" || true
        fail "未知断点阶段: ${CURRENT_PHASE}" 4
    fi

    verdict="$(parse_verdict "${REVIEW_FILE}")"
    if [[ -z "${verdict}" ]]; then
        print -r -- "\n[workflow] 评审开头 5 个非空行内没有合法 VERDICT，按 BLOCKED 处理。" >> "${REVIEW_FILE}"
        verdict="VERDICT: BLOCKED"
    fi

    if [[ "${verdict}" == "VERDICT: PASS" ]] && build_gate_passes; then
        assert_head_unchanged "最终发布门禁"
        CURRENT_PHASE="COMPLETE"
        write_checkpoint
        write_status "PASS" "${CURRENT_ROUND}" || true
        print "[workflow] 完成：Claude Code 评审通过，Java 8 verify 与测试门禁通过"
        print "[workflow] 产物目录：${RUN_DIR}"
        exit 0
    fi

    if [[ "${verdict}" == "VERDICT: BLOCKED" ]]; then
        write_status "BLOCKED" "${CURRENT_ROUND}" || true
        fail "流程被 Claude Code 判定为 BLOCKED。修正阻塞后可从断点恢复：${WORKFLOW_SCRIPT} --resume ${RUN_DIR}" 3
    fi

    if (( CURRENT_ROUND >= MAX_FIX_ROUNDS_VALUE )); then
        write_status "MAX_FIX_ROUNDS_REACHED" "${CURRENT_ROUND}" || true
        fail "达到最大修复轮次仍未通过。提高 MAX_FIX_ROUNDS 或修正环境后可恢复：${WORKFLOW_SCRIPT} --resume ${RUN_DIR}" 2
    fi

    if [[ "${verdict}" == "VERDICT: PASS" ]] && ! build_gate_passes; then
        {
            print -r -- ""
            print -r -- "## 自动门禁补充"
            print -r -- ""
            print -r -- "Claude Code 给出 PASS，但 Maven verify 或测试数量门禁未通过；本轮强制进入修复。"
            print -r -- "构建摘要见 ${BUILD_SUMMARY}。"
        } >> "${REVIEW_FILE}"
    fi

    (( CURRENT_ROUND++ ))
    printf -v fix_prefix '%02d' "$((CURRENT_ROUND * 3 + 1))"
    LATEST_SUMMARY="${RUN_DIR}/${fix_prefix}-codex-fix-r${CURRENT_ROUND}.md"
    CURRENT_PHASE="FIX_RUNNING"
    write_checkpoint
    print "[workflow] 开始 Codex 第 ${CURRENT_ROUND} 轮修复"
    if ! run_codex_fix \
            "${CURRENT_ROUND}" \
            "${REVIEW_FILE}" \
            "${BUILD_LOG}" \
            "${BUILD_SUMMARY}" \
            "${LATEST_SUMMARY}" \
            "${LATEST_SUMMARY%.md}-cli.log"; then
        write_status "CODEX_FIX_FAILED" "${CURRENT_ROUND}" || true
        fail "Codex 修复调用失败，可从断点恢复。产物目录: ${RUN_DIR}" 4
    fi
    assert_head_unchanged "第 ${CURRENT_ROUND} 轮修复"
    CURRENT_PHASE="FIX_DONE"
    write_checkpoint
done
