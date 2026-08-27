#!/bin/zsh

set -u
set -o pipefail
umask 077

readonly SEQUENCE_SCRIPT="${0:A}"
readonly WORKFLOW_ROOT="${0:A:h:h}"
readonly WORKER_SCRIPT="${WORKFLOW_ROOT}/scripts/claude-codex-workflow.zsh"
readonly COLLAB_ROOT="${WORKFLOW_ROOT}/.ai-collab"
readonly SEQUENCES_ROOT="${COLLAB_ROOT}/sequences"
readonly SEQUENCE_LOCK_DIR="${COLLAB_ROOT}/sequence.lock"

SEQUENCE_RUN_DIR=""
MANIFEST_SNAPSHOT=""
typeset -i CURRENT_INDEX=1
typeset -i TASK_COUNT=0
CURRENT_TASK=""
CURRENT_CHILD_RUN_DIR=""
LOCK_ACQUIRED=0
typeset -i CURRENT_CHILD_PID=0
CURRENT_CHILD_KIND=""

function usage() {
    print -u2 "用法："
    print -u2 "  ${SEQUENCE_SCRIPT} <任务顺序清单文件>"
    print -u2 "  ${SEQUENCE_SCRIPT} --resume <已有顺序运行目录>"
    print -u2 ""
    print -u2 "清单每行一个任务 Markdown 路径，支持工作区相对路径；空行和 # 注释会被忽略。"
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
    fail "${label}仍有子流程运行（PID=${child_pid}），拒绝并发恢复。请等待它结束或先安全终止该进程" 4
}

function track_current_child() {
    local child_pid="$1"
    local child_kind="$2"
    local temp_file="${SEQUENCE_RUN_DIR}/current-child.pid.tmp.$$"
    CURRENT_CHILD_PID="${child_pid}"
    CURRENT_CHILD_KIND="${child_kind}"
    print -r -- "${child_pid}" > "${temp_file}" || fail "无法记录当前子流程 PID" 4
    mv -f "${temp_file}" "${SEQUENCE_RUN_DIR}/current-child.pid" || fail "无法更新当前子流程 PID" 4
    print -r -- "${child_kind}" > "${SEQUENCE_RUN_DIR}/current-child.kind" || fail "无法记录当前子流程类型" 4
}

function clear_current_child() {
    local expected_pid="$1"
    local recorded_pid=""
    if [[ -f "${SEQUENCE_RUN_DIR}/current-child.pid" ]]; then
        recorded_pid="$(<"${SEQUENCE_RUN_DIR}/current-child.pid")"
    fi
    if [[ -z "${recorded_pid}" || "${recorded_pid}" == "${expected_pid}" ]]; then
        command rm -f "${SEQUENCE_RUN_DIR}/current-child.pid" "${SEQUENCE_RUN_DIR}/current-child.kind"
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
    if (( child_pid <= 0 )) && [[ -n "${SEQUENCE_RUN_DIR}" && -f "${SEQUENCE_RUN_DIR}/current-child.pid" ]]; then
        child_pid="$(<"${SEQUENCE_RUN_DIR}/current-child.pid")"
    fi
    if ! is_positive_integer "${child_pid}"; then
        return 0
    fi
    if kill -0 "${child_pid}" 2>/dev/null; then
        print -u2 "[sequence] 正在终止 ${CURRENT_CHILD_KIND:-子流程}（PID=${child_pid}）"
        kill -TERM "${child_pid}" 2>/dev/null || true
        for attempt in {1..40}; do
            if ! kill -0 "${child_pid}" 2>/dev/null; then
                break
            fi
            sleep 0.1
        done
        if kill -0 "${child_pid}" 2>/dev/null; then
            print -u2 "[sequence] 子流程未在 4 秒内退出，发送 KILL（PID=${child_pid}）"
            kill -KILL "${child_pid}" 2>/dev/null || true
        fi
        wait "${child_pid}" 2>/dev/null || true
    fi
    if ! kill -0 "${child_pid}" 2>/dev/null; then
        clear_current_child "${child_pid}"
    fi
}

function release_sequence_lock() {
    local owner_pid=""
    if [[ ! -d "${SEQUENCE_LOCK_DIR}" ]]; then
        return 0
    fi
    if [[ -f "${SEQUENCE_LOCK_DIR}/pid" ]]; then
        owner_pid="$(<"${SEQUENCE_LOCK_DIR}/pid")"
    fi
    if [[ "${owner_pid}" != "$$" ]]; then
        return 0
    fi
    if live_child_pid_for_run_dir "${SEQUENCE_RUN_DIR}" >/dev/null; then
        print -u2 "[sequence] 子流程仍存活，保留顺序锁: ${SEQUENCE_LOCK_DIR}"
        return 0
    fi
    command rm -f "${SEQUENCE_LOCK_DIR}/pid" "${SEQUENCE_LOCK_DIR}/run-dir"
    rmdir "${SEQUENCE_LOCK_DIR}" 2>/dev/null || true
}

function acquire_sequence_lock() {
    local owner_pid=""
    local owner_run_dir=""
    mkdir -p "${COLLAB_ROOT}" || fail "无法创建协作目录: ${COLLAB_ROOT}"
    if [[ -d "${SEQUENCE_LOCK_DIR}" ]]; then
        if [[ -f "${SEQUENCE_LOCK_DIR}/pid" ]]; then
            owner_pid="$(<"${SEQUENCE_LOCK_DIR}/pid")"
        fi
        if is_non_negative_integer "${owner_pid}" && kill -0 "${owner_pid}" 2>/dev/null; then
            fail "已有顺序总控流程正在运行（PID=${owner_pid}）" 4
        fi
        if [[ -f "${SEQUENCE_LOCK_DIR}/run-dir" ]]; then
            owner_run_dir="$(<"${SEQUENCE_LOCK_DIR}/run-dir")"
        fi
        if [[ -n "${owner_run_dir}" && "${owner_run_dir}" == "${COLLAB_ROOT}/"* ]]; then
            assert_no_live_child_for_run_dir "${owner_run_dir}" "原顺序总控"
        fi
        command rm -f "${SEQUENCE_LOCK_DIR}/pid" "${SEQUENCE_LOCK_DIR}/run-dir"
        if ! rmdir "${SEQUENCE_LOCK_DIR}" 2>/dev/null; then
            fail "顺序总控锁目录异常且无法安全清理: ${SEQUENCE_LOCK_DIR}" 4
        fi
    fi
    if ! mkdir "${SEQUENCE_LOCK_DIR}"; then
        fail "无法获取顺序总控锁: ${SEQUENCE_LOCK_DIR}" 4
    fi
    LOCK_ACQUIRED=1
    print -r -- "$$" > "${SEQUENCE_LOCK_DIR}/pid"
    print -r -- "${SEQUENCE_RUN_DIR}" > "${SEQUENCE_LOCK_DIR}/run-dir"
}

function write_sequence_state() {
    local sequence_status="$1"
    local last_exit="$2"
    local temp_file="${SEQUENCE_RUN_DIR}/sequence-state.txt.tmp.$$"
    {
        print -r -- "status=${sequence_status}"
        print -r -- "currentIndex=${CURRENT_INDEX}"
        print -r -- "taskCount=${TASK_COUNT}"
        print -r -- "currentTask=${CURRENT_TASK}"
        print -r -- "childRunDir=${CURRENT_CHILD_RUN_DIR}"
        print -r -- "lastExit=${last_exit}"
        print -r -- "updatedAt=$(date '+%Y-%m-%d %H:%M:%S %z')"
    } > "${temp_file}" || fail "无法写入顺序断点" 4
    mv -f "${temp_file}" "${SEQUENCE_RUN_DIR}/sequence-state.txt" \
        || fail "无法更新顺序断点" 4
}

function record_completed_module() {
    local completed_file="${SEQUENCE_RUN_DIR}/completed-modules.tsv"
    local temp_file="${completed_file}.tmp.$$"
    if [[ -f "${completed_file}" ]]; then
        awk -F '\t' -v completed_index="${CURRENT_INDEX}" \
            '$2 != completed_index { print }' "${completed_file}" > "${temp_file}" \
            || fail "无法整理已完成模块台账" 4
    else
        : > "${temp_file}"
    fi
    printf '%s\t%s\t%s\tPASS\n' "$(date '+%Y-%m-%d %H:%M:%S %z')" \
        "${CURRENT_INDEX}" "${CURRENT_TASK}" >> "${temp_file}" \
        || fail "无法记录已完成模块" 4
    mv -f "${temp_file}" "${completed_file}" || fail "无法原子更新已完成模块台账" 4
}

function handle_signal() {
    local signal_name="$1"
    local exit_code="$2"
    trap - HUP INT TERM
    terminate_current_child
    if [[ -n "${SEQUENCE_RUN_DIR}" && -d "${SEQUENCE_RUN_DIR}" ]]; then
        write_sequence_state "INTERRUPTED_${signal_name}" "${exit_code}" || true
    fi
    print -u2 "[sequence] 收到 ${signal_name}，已保留断点。恢复命令：${SEQUENCE_SCRIPT} --resume ${SEQUENCE_RUN_DIR}"
    exit "${exit_code}"
}

function cleanup() {
    if (( LOCK_ACQUIRED == 1 )); then
        release_sequence_lock
    fi
}

trap 'handle_signal HUP 129' HUP
trap 'handle_signal INT 130' INT
trap 'handle_signal TERM 143' TERM
trap 'cleanup' EXIT

# 总控进程统一持有 caffeinate，子流程继承标志后不会重复启动 caffeinate。
if [[ "${COLLAB_CAFFEINATED:-0}" != "1" \
        && "${COLLAB_DISABLE_CAFFEINATE:-0}" != "1" \
        && -x /usr/bin/caffeinate ]]; then
    export COLLAB_CAFFEINATED=1
    exec /usr/bin/caffeinate -i "${SEQUENCE_SCRIPT}" "$@"
    fail "无法通过 caffeinate 启动顺序流程" 4
fi

if [[ ! -x "${WORKER_SCRIPT}" ]]; then
    fail "单模块执行器不存在或不可执行: ${WORKER_SCRIPT}"
fi

MODE="NEW"
MANIFEST_FILE=""
if (( $# == 2 )) && [[ "$1" == "--resume" ]]; then
    MODE="RESUME"
    SEQUENCE_RUN_DIR="$(resolve_under_collab_root "$2")" \
        || fail "顺序恢复目录必须位于 ${COLLAB_ROOT} 内"
elif (( $# == 1 )); then
    MANIFEST_FILE="$(resolve_under_workspace "$1")" \
        || fail "任务清单必须位于工作区内: ${WORKFLOW_ROOT}"
else
    usage
    exit 4
fi

if [[ "${MODE}" == "NEW" ]]; then
    if [[ ! -f "${MANIFEST_FILE}" ]]; then
        fail "任务清单不存在: ${MANIFEST_FILE}"
    fi
    mkdir -p "${SEQUENCES_ROOT}" || fail "无法创建顺序运行目录"
    readonly SEQUENCE_ID="$(date '+%Y%m%d-%H%M%S')-$$"
    SEQUENCE_RUN_DIR="${SEQUENCES_ROOT}/${SEQUENCE_ID}"
    if ! mkdir "${SEQUENCE_RUN_DIR}"; then
        fail "无法创建顺序运行目录: ${SEQUENCE_RUN_DIR}"
    fi
    mkdir -p "${SEQUENCE_RUN_DIR}/modules" || fail "无法创建模块产物目录"
    MANIFEST_SNAPSHOT="${SEQUENCE_RUN_DIR}/manifest.snapshot"
    : > "${MANIFEST_SNAPSHOT}"

    while IFS= read -r manifest_line || [[ -n "${manifest_line}" ]]; do
        trimmed_line="${manifest_line#"${manifest_line%%[![:space:]]*}"}"
        if [[ -z "${trimmed_line}" || "${trimmed_line}" == \#* ]]; then
            continue
        fi
        if [[ "${trimmed_line}" == /* ]]; then
            task_candidate="${trimmed_line}"
        else
            task_candidate="${WORKFLOW_ROOT}/${trimmed_line}"
        fi
        task_path="$(resolve_under_workspace "${task_candidate}")" \
            || fail "任务文件必须位于工作区内: ${trimmed_line}"
        if [[ ! -f "${task_path}" || "${task_path}" != *.md ]]; then
            fail "任务必须是存在的 Markdown 文件: ${trimmed_line}"
        fi
        print -r -- "${task_path}" >> "${MANIFEST_SNAPSHOT}"
    done < "${MANIFEST_FILE}"

    TASK_COUNT="$(wc -l < "${MANIFEST_SNAPSHOT}" | tr -d '[:space:]')"
    if ! is_non_negative_integer "${TASK_COUNT}" || (( TASK_COUNT == 0 )); then
        fail "任务清单中没有可执行任务"
    fi
    cp -p "${MANIFEST_FILE}" "${SEQUENCE_RUN_DIR}/manifest.original" \
        || fail "无法保存原始任务清单"
    CURRENT_INDEX=1
    CURRENT_TASK=""
    CURRENT_CHILD_RUN_DIR=""
    write_sequence_state "READY" 0
else
    if [[ ! -d "${SEQUENCE_RUN_DIR}" ]]; then
        fail "顺序恢复目录不存在: ${SEQUENCE_RUN_DIR}"
    fi
    MANIFEST_SNAPSHOT="${SEQUENCE_RUN_DIR}/manifest.snapshot"
    state_file="${SEQUENCE_RUN_DIR}/sequence-state.txt"
    if [[ ! -f "${MANIFEST_SNAPSHOT}" || ! -f "${state_file}" ]]; then
        fail "顺序恢复目录缺少清单快照或断点文件"
    fi
    previous_status="$(read_state_value "${state_file}" "status")"
    if [[ "${previous_status}" == "PASS" ]]; then
        print "[sequence] 该顺序流程已经全部完成：${SEQUENCE_RUN_DIR}"
        exit 0
    fi
    CURRENT_INDEX="$(read_state_value "${state_file}" "currentIndex")"
    TASK_COUNT="$(read_state_value "${state_file}" "taskCount")"
    CURRENT_TASK="$(read_state_value "${state_file}" "currentTask")"
    CURRENT_CHILD_RUN_DIR="$(read_state_value "${state_file}" "childRunDir")"
    if ! is_non_negative_integer "${CURRENT_INDEX}" \
            || ! is_non_negative_integer "${TASK_COUNT}" \
            || (( CURRENT_INDEX < 1 || TASK_COUNT < 1 || CURRENT_INDEX > TASK_COUNT )); then
        fail "顺序断点文件损坏: ${state_file}"
    fi
    print "[sequence] 从第 ${CURRENT_INDEX}/${TASK_COUNT} 个模块恢复：${SEQUENCE_RUN_DIR}"
fi

assert_no_live_child_for_run_dir "${SEQUENCE_RUN_DIR}" "该顺序运行目录"
acquire_sequence_lock
export COLLAB_SEQUENCE_OWNER_PID="$$"

while (( CURRENT_INDEX <= TASK_COUNT )); do
    CURRENT_TASK="$(sed -n "${CURRENT_INDEX}p" "${MANIFEST_SNAPSHOT}")"
    if [[ -z "${CURRENT_TASK}" || ! -f "${CURRENT_TASK}" ]]; then
        write_sequence_state "INVALID_TASK" 4 || true
        fail "第 ${CURRENT_INDEX} 个任务文件不存在: ${CURRENT_TASK}" 4
    fi

    module_number="$(printf '%03d' "${CURRENT_INDEX}")"
    CURRENT_CHILD_RUN_DIR="${SEQUENCE_RUN_DIR}/modules/${module_number}"
    module_console_log="${SEQUENCE_RUN_DIR}/module-${module_number}-console.log"
    write_sequence_state "RUNNING" 0
    print "[sequence] 开始第 ${CURRENT_INDEX}/${TASK_COUNT} 个模块：${CURRENT_TASK}"

    typeset -i module_exit=0
    if [[ -f "${CURRENT_CHILD_RUN_DIR}/workflow-checkpoint.txt" ]]; then
        "${WORKER_SCRIPT}" --resume "${CURRENT_CHILD_RUN_DIR}" \
            >> "${module_console_log}" 2>&1 &
    else
        "${WORKER_SCRIPT}" --run-dir "${CURRENT_CHILD_RUN_DIR}" "${CURRENT_TASK}" \
            >> "${module_console_log}" 2>&1 &
    fi
    module_pid=$!
    track_current_child "${module_pid}" "module-${module_number}"
    wait_current_child "${module_pid}" || module_exit=$?

    if (( module_exit != 0 )); then
        write_sequence_state "STOPPED" "${module_exit}" || true
        print -u2 "[sequence] 第 ${CURRENT_INDEX} 个模块未通过（exit=${module_exit}），顺序流程停止。"
        print -u2 "[sequence] 处理问题后恢复：${SEQUENCE_SCRIPT} --resume ${SEQUENCE_RUN_DIR}"
        exit "${module_exit}"
    fi

    record_completed_module
    (( CURRENT_INDEX++ ))
    CURRENT_TASK=""
    CURRENT_CHILD_RUN_DIR=""
    if (( CURRENT_INDEX <= TASK_COUNT )); then
        write_sequence_state "READY" 0
    fi
done

write_sequence_state "PASS" 0
print "[sequence] 全部 ${TASK_COUNT} 个模块已按顺序完成"
print "[sequence] 产物目录：${SEQUENCE_RUN_DIR}"
exit 0
