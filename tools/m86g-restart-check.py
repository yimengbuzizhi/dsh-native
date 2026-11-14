"""M86-G durable queue acceptance across two real Android process restarts.

Authoring or AST parsing this file never invokes adb. Run only after explicit review,
with the current debug APK installed and exclusive, serial use of the device:
  python tools/m86g-restart-check.py --out docs/m86g-restart-NEW

Reuses m86g-ui-check.Probe's permission-free provider, UI actions, unique debug
observations and cleanup. Creates exactly one new fixture chat and retains it and
its plugin. Never reads preferences, keys, old chat bodies, session files or full
projection dumps, and never deletes sessions. Both force-stops require all global
writers/turns idle and the exact new fixture session confirmed first. A paused
entry remains intact on failure; it is not cancelled or silently resumed in cleanup.

Dependency: installed debug dsh_ui_probe must expose the current Probe schema
(sessionId/fixtureSession/global counts/messages/queued/delivered/turnEnds/sendMode).
force-stop may detach accessibility; the serial device owner must confirm its Bound
state afterwards. This script does not change accessibility or other app settings.
"""
import argparse
import importlib.util
import json
from pathlib import Path
import re
import sys
import time

ROOT = Path(__file__).resolve().parents[1]


def load_probe_module():
    spec = importlib.util.spec_from_file_location('m86g_ui_restart_dependency', ROOT / 'tools' / 'm86g-ui-check.py')
    if spec is None or spec.loader is None:
        raise RuntimeError('missing_m86g_ui_probe_module')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)  # Its main guard prevents any adb at import time.
    return module


class RestartCheck:
    def __init__(self, module, args):
        self.g = module
        self.p = module.Probe(args)
        # Separate fixture/artifact namespace while retaining Probe's safe marker grammar.
        self.p.plugin = 'm86g.restart.fixture.' + self.p.stamp.replace('-', '')
        self.p.provider = self.p.plugin + '.llm'
        self.p.remote = '/sdcard/Download/m86g-restart-' + self.p.stamp
        self.session_id = None
        self.entry_id = None
        self.initial_turn_ends = None
        self.completed_turn_ends = None
        self.processes = []
        self.phases = []
        self.force_stopped = False
        self.force_stop_attempted = False
        self.baseline_session_id = None

    @staticmethod
    def idle(state):
        return (all(state.get(key) == 0 for key in ('activeTurnCount', 'writerCount', 'streamingCount'))
                and state.get('streaming') is False and state.get('stopping') is False)

    @staticmethod
    def no_draft(state):
        return (state.get('hasDraft') is False and state.get('pendingImageCount') == 0
                and state.get('pendingFileCount') == 0)

    def fail(self, name):
        self.p.check(name, False)

    def note(self, phase, state):
        # State only contains the debug API's bounded M86 fixture observation.
        self.phases.append({'phase': phase, 'observationSerial': self.p.serial,
                            'sessionId': state.get('sessionId'),
                            'turnEnds': list(state.get('turnEnds', [])),
                            'queueIds': [entry.get('id') for entry in state.get('queued', [])],
                            'delivered': list(state.get('delivered', []))})

    def fixture(self, state):
        return (state.get('fixtureSession') is True and state.get('sessionId') == self.session_id
                and state.get('shell') == 'native'
                and any(message.get('role') == 'user' and message.get('text') == self.p.marker
                        for message in state.get('messages', [])))

    def confirm_fixture(self, name, state):
        self.p.check(name + '_exact_created_fixture', self.fixture(state))
        return state

    def wait_state(self, name, predicate, timeout=30):
        deadline = time.monotonic() + timeout
        while True:
            state = self.p.state()
            if predicate(state):
                self.p.check(name, True)
                return state
            if time.monotonic() >= deadline:
                self.fail(name)
            time.sleep(.2)

    def pids(self):
        # pidof exit 1 with empty stdout means absent; transport/parse errors are failures.
        rc, raw = self.p.shell('pidof', self.g.PKG, allow_failure=True, timeout=10)
        value = raw.decode('ascii', errors='strict').strip()
        if rc == 1 and not value:
            return []
        if rc != 0 or not re.fullmatch(r'[0-9]+(?:\s+[0-9]+)*', value):
            raise self.g.UiFailure('invalid_pidof_result')
        return sorted(int(pid) for pid in value.split())

    def wait_process(self, name, present, timeout=20):
        deadline = time.monotonic() + timeout
        while True:
            values = self.pids()
            if bool(values) == present:
                self.p.check(name, True)
                return values
            if time.monotonic() >= deadline:
                self.fail(name)
            time.sleep(.2)

    def preflight(self):
        self.p.preflight()
        state = self.p.state()
        self.p.check('restart_preflight_all_global_writers_idle', self.idle(state))
        self.p.check('restart_preflight_no_unsaved_inputs', self.no_draft(state))
        self.baseline_session_id = state.get('sessionId')
        self.p.original_mode = state.get('sendMode')
        self.p.check('restart_original_send_mode_observed', self.p.original_mode in ('queue', 'steer'))
        agents = self.p.dump('dsh_agent_list')
        roles = self.p.dump('dsh_roles_list')
        active = roles.get('active', {})
        self.p.check('restart_requires_builtin_agent', agents.get('active', {}).get('builtin') is True)
        self.p.check('restart_requires_builtin_durable_storage_and_prompt',
                     roles.get('store') == 'file' and roles.get('storeReadOnly') is False
                     and active.get('storage') == 'builtin.storage.jsonl'
                     and active.get('storageReadOnly') is False
                     and active.get('prompt') == 'builtin.prompt.dsh'
                     and not active.get('storageSafety') and not active.get('promptSafety'))

    def exact_pending(self, state):
        entries = state.get('queued', [])
        return (len(entries) == 1 and entries[0].get('id') == self.entry_id
                and entries[0].get('text') == self.p.current_input
                and entries[0].get('delivery') == 'queue'
                and entries[0].get('status') == 'pending' and entries[0].get('paused') is True)

    def undelivered(self, state):
        messages = state.get('messages', [])
        return (state.get('delivered', []).count(self.entry_id) == 0
                and not any(message.get('role') == 'user' and message.get('text') == self.p.current_input
                            for message in messages)
                and not any(message.get('role') == 'assistant'
                            and message.get('text') == 'M86GUI_REPLY_' + self.p.current_input
                            for message in messages))

    def completed_once(self, state):
        messages = state.get('messages', [])
        return (self.fixture(state) and self.idle(state) and self.no_draft(state)
                and state.get('errorPresent') is False and state.get('queued') == []
                and state.get('delivered', []).count(self.entry_id) == 1
                and sum(message.get('role') == 'user' and message.get('text') == self.p.current_input
                        for message in messages) == 1
                and sum(message.get('role') == 'assistant'
                        and message.get('text') == 'M86GUI_REPLY_' + self.p.current_input
                        for message in messages) == 1
                and state.get('turnEnds') == self.initial_turn_ends + ['completed'])

    def setup_paused_queue(self):
        self.p.make_fixture()
        # Capture was done before any mode mutation, through the safe state observation.
        self.p.behavior('queue')
        current = self.p.hold('process_restart')
        self.session_id = self.p.capture_session_id()
        self.p.check('restart_fixture_is_a_new_recorded_chat', self.p.session_ids == [self.session_id]
                     and self.session_id != self.baseline_session_id)
        current = self.p.draft_and_send('process_restart_' + self.p.stamp.replace('-', ''), 'queue', current)
        self.entry_id = self.p.queued_id
        self.p.check('restart_pending_entry_has_stable_id', isinstance(self.entry_id, str) and bool(self.entry_id))
        self.p.stop_fixture(current)
        state = self.wait_state('restart_stopped_fixture_writer_completed',
                                lambda value: self.fixture(value) and self.idle(value))
        self.confirm_fixture('restart_before_first_stop', state)
        self.p.check('restart_stop_saved_exact_paused_queue', self.exact_pending(state) and self.undelivered(state))
        self.p.check('restart_stop_closed_aborted_hold_turn', state.get('turnEnds') == ['aborted'])
        self.initial_turn_ends = list(state['turnEnds'])
        self.note('paused-before-process-stop', state)
        self.p.shot('paused-before-process-stop')

    def force_stop(self, phase):
        state = self.confirm_fixture(phase, self.p.state())
        self.p.check(phase + '_requires_no_active_global_writer', self.idle(state) and self.no_draft(state))
        if phase == 'first_process_stop':
            self.p.check(phase + '_exact_queue_still_paused', self.exact_pending(state) and self.undelivered(state))
        else:
            self.p.check(phase + '_successful_delivery_still_exactly_once', self.completed_once(state))
        before = self.wait_process(phase + '_pid_present', True)
        self.force_stopped = True  # Cleanup must cold-start even if adb delivery is uncertain.
        self.force_stop_attempted = True
        self.p.shell('am', 'force-stop', self.g.PKG)
        # No am-start/dump call is allowed until absence was observed and recorded.
        self.p.fixture_foreground = False
        after = self.wait_process(phase + '_pid_absent', False)
        self.processes.append({'phase': phase, 'beforePids': before, 'afterPids': after,
                               'absenceConfirmed': True})

    def cold_open(self, phase):
        self.p.start(dsh_shell='native', dsh_llm_provider='off', dsh_model=self.p.provider,
                     dsh_open_screen='chat', dsh_open_session=self.session_id, settle=.4)
        values = self.wait_process(phase + '_new_process_present', True)
        if self.processes and self.processes[-1].get('absenceConfirmed'):
            self.processes[-1]['coldStartPids'] = values
            self.p.check(phase + '_old_pid_not_reused', not set(values).intersection(self.processes[-1]['beforePids']))
        state = self.wait_state(phase + '_same_fixture_loaded', self.fixture, timeout=45)
        self.force_stopped = False
        providers = self.p.dump('dsh_dump_providers')
        self.p.check(phase + '_own_provider_selected', providers.get('llmSelected') == self.p.provider
                     and not providers.get('llmActive') and self.p.provider in providers.get('llm', []))
        self.note(phase, state)
        return state

    def verify_paused(self, name):
        state = self.confirm_fixture(name, self.p.state())
        self.p.check(name + '_same_entry_id_pending_paused', self.exact_pending(state))
        self.p.check(name + '_no_automatic_delivery', self.idle(state) and self.undelivered(state)
                     and state.get('turnEnds') == self.initial_turn_ends)
        self.note(name, state)
        return state

    def dwell(self, name, check, seconds=3):
        # Observe throughout the window, instead of sleeping once and missing transient work.
        deadline = time.monotonic() + seconds
        count = 0
        while True:
            state = check(name + '_' + str(count))
            count += 1
            if count >= 2 and time.monotonic() >= deadline:
                self.p.check(name + '_observed_window', True)
                return state
            time.sleep(.2)

    def resume_once(self):
        current = self.p.wait_nodes('restart_paused_queue_has_ui_continue', lambda nodes:
                                   self.p.text_exists(nodes, '待发送 1 条')
                                   and self.p.text_exists(nodes, '已暂停：', contains=True)
                                   and self.p.text_exists(nodes, '继续发送'))
        self.verify_paused('restart_before_manual_continue')
        self.p.shot('after-first-cold-start-paused')
        self.p.fixture_foreground = True
        self.p.tap('继续发送', current=current)
        state = self.wait_state('restart_manual_continue_completes_once', self.completed_once, timeout=35)
        self.p.assert_delivery('restart_manual_continue', True)
        self.p.fixture_foreground = False
        self.completed_turn_ends = list(state['turnEnds'])
        self.note('completed-before-second-stop', state)
        self.p.shot('after-manual-continue-completed')

    def verify_no_replay(self, name):
        state = self.confirm_fixture(name, self.p.state())
        self.p.check(name + '_delivery_and_unique_reply_still_once', self.completed_once(state))
        self.p.check(name + '_no_additional_turn_after_restart', state.get('turnEnds') == self.completed_turn_ends)
        self.note(name, state)
        return state

    def cleanup(self):
        if not self.p.mutated:
            return
        try:
            if self.force_stopped:
                # Reopen only this run's recorded chat; never synthesize another send.
                if self.session_id is None:
                    raise self.g.UiFailure('cleanup_missing_created_fixture_id')
                self.cold_open('cleanup_reopen_fixture')
            state = self.p.state()
            if self.session_id is None and self.p.marker is not None:
                candidate_id = state.get('sessionId')
                if (state.get('fixtureSession') is True and candidate_id
                        and candidate_id != self.baseline_session_id
                        and any(message.get('role') == 'user' and message.get('text') == self.p.marker
                                for message in state.get('messages', []))):
                    self.session_id = candidate_id
                    if candidate_id not in self.p.session_ids:
                        self.p.session_ids.append(candidate_id)
                    self.p.check('cleanup_captured_exact_started_fixture', True)
            if not self.idle(state):
                self.confirm_fixture('cleanup_running_target', state)
                self.p.fixture_foreground = True
                self.p.stop_fixture()  # The shared helper additionally checks the exact HOLD marker.
            else:
                self.p.fixture_foreground = False
        except Exception as error:
            identifier = str(error) if isinstance(error, self.g.UiFailure) else type(error).__name__
            self.p.failures.append('cleanup_fixture_identity_or_stop_' + identifier)
            # Do not let shared cleanup stop a target that was not independently confirmed.
            self.p.fixture_foreground = False
        self.p.cleanup()
        if self.entry_id is not None:
            try:
                state = self.p.state()
                self.confirm_fixture('cleanup_queue_target', state)
                remaining = [entry for entry in state.get('queued', []) if entry.get('id') == self.entry_id]
                delivered = state.get('delivered', []).count(self.entry_id)
                retained = (delivered == 0 and len(remaining) == 1
                            and remaining[0].get('status') == 'pending' and remaining[0].get('paused') is True)
                acknowledged = delivered == 1 and not remaining
                self.p.check('cleanup_retains_unfinished_queue_or_single_ack', retained or acknowledged)
            except Exception as error:
                identifier = str(error) if isinstance(error, self.g.UiFailure) else type(error).__name__
                self.p.failures.append('cleanup_queue_observation_' + identifier)

    def run(self):
        try:
            self.preflight()
            self.setup_paused_queue()
            self.force_stop('first_process_stop')
            self.cold_open('first_cold_start')
            self.dwell('recovered_queue_remains_paused', self.verify_paused)
            self.resume_once()
            self.force_stop('second_process_stop')
            self.cold_open('second_cold_start')
            self.dwell('second_restart_has_no_replay', self.verify_no_replay)
            self.p.shot('after-second-cold-start-no-replay')
        except Exception as error:
            identifier = str(error) if isinstance(error, self.g.UiFailure) else type(error).__name__
            self.p.failures.append(identifier)
            if not self.force_stopped and self.session_id is not None:
                try:
                    if self.fixture(self.p.state()):
                        self.p.shot('failure-confirmed-fixture')
                except Exception:
                    pass
        finally:
            try:
                self.cleanup()
            except Exception as error:
                self.p.failures.append('cleanup_unhandled_' + type(error).__name__)
            report = {'fixture': 'M86-G-real-process-restart', 'version': 1,
                      'pluginId': self.p.plugin, 'providerId': self.p.provider,
                      'sessionId': self.session_id, 'queuedPromptId': self.entry_id,
                      'marker': self.p.marker, 'input': self.p.current_input,
                      'originalSendMode': self.p.original_mode,
                      'createdSessionIds': self.p.session_ids,
                      'processRestarts': self.processes, 'phases': self.phases,
                      'checks': self.p.checks, 'screenshots': self.p.screenshots,
                      'failures': self.p.failures,
                      'passed': not self.p.failures and all(item['passed'] for item in self.p.checks),
                      'accessibilityBoundRecheckRequired': self.force_stop_attempted}
            (self.p.out / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
        print(json.dumps({'passed': report['passed'], 'checks': len(self.p.checks),
                          'failures': self.p.failures, 'report': str(self.p.out / 'report.json')},
                         ensure_ascii=False), flush=True)
        return 0 if report['passed'] else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default=r'C:\Android\platform-tools\adb.exe')
    parser.add_argument('--device', default='192.0.2.10:39905')
    parser.add_argument('--out', type=Path, required=True, help='new evidence directory; must not exist')
    args = parser.parse_args()
    return RestartCheck(load_probe_module(), args).run()


if __name__ == '__main__':
    sys.exit(main())
