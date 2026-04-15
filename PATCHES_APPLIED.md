# Barometer patch notes

В этой версии:

## Исправления прошлой волны
1. Убрана двойная запись события `ALARM` в `SensorService`.
2. Исправлен режим графика `Raw`: теперь без сглаживания используется `LINEAR`, а не `CUBIC_BEZIER`.
3. Исправлена обработка кастомного диапазона дат в `GraphActivity`: выбор дат из `MaterialDatePicker` переводится из UTC picker millis в локальные границы суток.
4. Исправлен парсинг дробных настроек для локалей с запятой:
   - `pref_alert_drop`
   - `pref_ref_altitude_m`
5. Уменьшен риск `IllegalStateException` в `SettingsFragment`: фоновая работа больше не опирается на `requireContext()` / `requireActivity()`.
6. Для GPS-базовой высоты добавлена проверка `hasAltitude()`.
7. Broadcast с новым измерением ограничен пакетом приложения через `Intent#setPackage(...)`.
8. Room переведён на миграцию `1 -> 2` вместо destructive migration.
9. Для Android 14+ foreground service переведён на обязательный тип `specialUse`, чтобы убрать краш `MissingForegroundServiceTypeException`.

## Что добавлено сейчас
1. Явный переключатель записи на главном экране.
2. Настройка `pref_recording_enabled` в настройках.
3. Остановка записи теперь реально стопает сервис, а запуск поднимает его снова.
4. В foreground-уведомление добавлена кнопка паузы записи.
5. `BootReceiver` теперь учитывает не только автозапуск, но и включён ли вообще режим записи.
6. Отдельный экран состояния датчика (`StatusActivity`):
   - наличие барометра
   - состояние записи
   - состояние сервиса
   - интервал опроса
   - лимит истории
   - число записей в БД
   - время последнего измерения
   - уведомления
   - батарея / оптимизации
   - автозапуск
   - модель устройства
7. Добавлен мастер калибровки (`CalibrationWizardActivity`):
   - базовая высота по барометру
   - базовая высота по GPS
   - ручной ввод высоты
   - сброс базы в `0 м`
8. Добавлены подсказки по защите от выгрузки системой:
   - объяснение, что включить на Xiaomi / MIUI / HyperOS и других прошивках
   - быстрый переход к настройкам батареи
   - попытка открыть vendor-specific экраны автозапуска/фоновой работы

## Что не подтверждено автоматически
- Полная сборка `assembleDebug` в этой среде не была выполнена, потому что Gradle wrapper пытается скачать `gradle-8.7-bin.zip`, а интернет в контейнере недоступен.
- Поэтому это patched source archive, а не подтверждённый собранный APK.

## Что добавлено в этой волне
1. Реализована адаптивная запись в `SensorService`:
   - сенсор продолжает читаться как раньше
   - решение о сохранении в БД теперь зависит от недавнего изменения давления и времени с последней сохранённой точки
2. Добавлена простая модель режимов записи:
   - `спокойный`
   - `обычный`
   - `быстрый`
   - а также служебные состояния `фиксированный` и `остановлен`
3. Добавлена настройка `pref_adaptive_recording_enabled`.
4. На экран состояния добавлены:
   - статус адаптивной записи
   - текущий режим записи
5. Исправлен оставшийся build bug в `activity_main.xml`: удалён дублирующийся `btnStatus`.

## Что добавлено в этой волне (режим поездки)
1. Добавлен переключатель `pref_trip_mode_enabled` в настройках.
2. Во время режима поездки прогноз на главном экране отключается и явно показывает состояние `Режим поездки`.
3. При выключении режима поездки сохраняется новая точка отсчёта прогноза.
4. `ForecastEngine` теперь умеет считать прогноз только по данным, накопленным после заданного baseline timestamp.
5. Пока baseline активен, сравнение `как вчера` не показывается, чтобы не смешивать старые и новые данные.
6. На экране состояния к строке режима записи добавляется отметка `поездка`, если режим поездки включён.


## v6 Forecast overhaul
- Полностью переработан ForecastEngine: устойчивые окна 1/3/6ч, сглаживание, линейный тренд, шум, покрытие окна, согласованность сигнала
- Добавлен ForecastResult и отдельные модели confidence/quality
- Введены новые состояния прогноза: no data / insufficient / unstable / stable / slow/fast improving / slow/fast worsening
- Добавлены reason codes и динамическое объяснение прогноза
- Полностью обновлена карточка прогноза: subtitle, confidence bar, quality/noise chips, причины вывода

- v7: added a home screen widget with live pressure, forecast headline, 1h/3h deltas, sparkline, quick open/graph/toggle/refresh actions, previewLayout, and widget refresh hooks from service/settings/boot.

## v8 Умные уведомления + тихий режим
- Убрана зависимость от старого простого алерта по порогу падения давления.
- Добавлен отдельный движок умных уведомлений поверх Forecast v2:
  - быстрое ухудшение
  - устойчивое ухудшение
  - стабилизация после спада
  - улучшение после спада
- Уведомления теперь работают по переходам состояний и с подтверждением во времени, а не по одному скачку.
- Добавлен антиспам:
  - минимальный интервал между уведомлениями
  - cooldown по типу события
  - активное событие не шлётся повторно, пока продолжается
- Добавлен «Тихий режим»:
  - выключен
  - включение/выключение вручную
  - по расписанию
- В настройках добавлены:
  - включение умных уведомлений
  - чувствительность
  - минимальный интервал между уведомлениями
  - тихий режим и его расписание
- Сервис теперь сам оценивает ForecastResult и отправляет уведомления через отдельный канал.
- События уведомлений пишутся в EventSample и отображаются на графике.
- На экране статуса теперь видно:
  - включены ли умные уведомления
  - разрешение на уведомления
  - состояние тихого режима
  - последнее отправленное уведомление

- v9: returned ordinary notifications as a first-class mode alongside smart notifications; added notification mode selector (off/simple/smart), simple rise/fall threshold notifications, shared silent mode, status/graph labels, and legacy smart-mode migration.

- v10 graph UX overhaul: rebuilt Graph screen header, split controls into two rows, replaced text stats with stat cards, added bottom-sheet statistics and timeline with jump-to-event behavior.

## v11 graph premium polish
- Added contextual header chips on graph screen: mode, line style, event count
- Added accent-colored header action buttons
- Added event-specific timeline icons, badges, accent strip and chevron affordance
- Added dynamic delta coloring in summary cards and stats sheet
- Added new drawable resources for timeline event iconography

## v10 refresh requested by user
- Reverted graph screen back to the v10 direction and removed the later premium-graph polish.
- Main screen:
  - moved pressure/altitude text directly under the gauge and above the forecast card
  - removed the Status button from the main screen
  - removed the recording toggle/card from the main screen (recording remains in Settings)
- Graph screen:
  - removed duplicate summary stat cards from the main graph screen
  - removed the textual summary line from the statistics bottom sheet
  - removed smoothing entirely from the graph screen and forced linear rendering to avoid loops
  - removed the whole “Как показывать” control block
  - timeline now also includes trip mode on/off events in addition to logged notifications, calibration, GPS baseline and alarms
- Notifications settings:
  - cleaned labels so mode-specific settings no longer start with “Обычные:” / “Умные:”
- Widget:
  - removed widget receiver from manifest
  - removed widget provider/updater code paths from app logic
  - deleted widget source package and widget layout/xml/drawable files

## v10 stats/timeline overhaul
- Upgraded period statistics sheet into a real analytics panel for the selected range.
- Added new metrics:
  - amplitude for the period
  - fastest drop over an adaptive window
  - fastest rise over an adaptive window
  - stability / instability label
  - point count
  - ordinary notification count
  - smart notification count
  - trip count
  - calibration/GPS baseline count
- Added comparison against the previous equal-length period:
  - average delta vs previous period
  - amplitude delta vs previous period
  - event-count delta vs previous period
- Added a short human-readable insight summary at the top of the statistics sheet.
- Made fastest-drop and fastest-rise cards clickable to jump back to the relevant point on the graph.
- Reworked timeline into a human-readable event history:
  - grouped by day (Сегодня / Вчера / date)
  - custom human titles for notification/trip/calibration/GPS/alarm events
  - contextual subtitles instead of raw log-like rows
  - retained tap-to-jump behavior back to the graph.

- v10 + trip-mode suggestion push: added push suggestion for trip mode on motion-like unstable pressure pattern, 1h anti-spam, settings switch, service actions for Enable/Not now.

## Crash/report feedback loop
- Added local ring-buffer app event log (`AppEventLogger`) with the most recent ~200 events.
- Added uncaught-crash capture (`IssueReportManager`): on crash the app writes a pending crash report file with stacktrace and recent app log.
- On the next launch, `MainActivity` now offers to send that crash report to the developer by email.
- Added a new Settings action: `Сообщить о проблеме`.
- Manual issue report now prepares a text tech report and opens the user's email app with:
  - recipient `gordey.chernyshe@gmail.com`
  - prefilled subject/body
  - attached report file via `FileProvider`
- Added `FileProvider` manifest entry and `res/xml/file_paths.xml` for safe attachment sharing.

- Expanded local feedback logging: graph interactions, more settings changes, report generation/email intents, and recording start/stop are now added to the in-app event journal (400-line buffer).

## Onboarding visual refresh
- Rebuilt onboarding into a richer 7-slide flow.
- Added dedicated slides for adaptive recording and export data.
- Replaced plain icon-only pages with preview cards, feature chips and mini mock stats.
- Added a subtle ViewPager scale/alpha transform for smoother onboarding transitions.

## v10 stability + data-integrity fixes
- Fixed a real data-integrity bug in `SensorService`: a sample is no longer marked as saved **before** the Room write succeeds.
- Added pending-save protection in `SensorService` so slow I/O does not enqueue duplicate sample writes for the same moment.
- Clamped history-size parsing to a minimum sane value to avoid accidental zero/negative retention edge cases.
- Added Room indices on `pressure_samples.timestamp` and `events.timestamp`.
- Added Room migration `2 -> 3` to create those indices on existing installs without destructive reset.
- Normalized stale AGP version metadata in `gradle/libs.versions.toml` to match the actual project plugin line.
- Replaced several silent background-task failures with explicit in-app logging via `AppEventLogger`.
- Hardened `MainActivity`, `GraphActivity`, `CalibrationWizardActivity`, `StatusActivity` and `IssueReportManager` against UI callbacks arriving after the screen is already finishing/destroyed.
- Reworked receiver unregister logic in `MainActivity` to avoid blind exception swallowing.
- Added logging when sensor listener registration or report/email helpers fail instead of losing those errors silently.

## Onboarding real-UI compact rebuild
- Replaced the generic onboarding card/chips/stats template with 7 dedicated compact mock screens.
- Each slide now shows a mini version of a real screen area instead of placeholder marketing blocks.
- Removed decorative footer tags and generic feature chips from the bottom content area.
- Bottom section is now reduced to a single title and short explanation text.
- Added dedicated compact previews for:
  - home screen
  - graph/history screen
  - notifications + push preview
  - trip mode prompt/state
  - adaptive recording settings
  - export/data screen
  - theme + report-problem screen
- Onboarding visuals now rely on theme attributes so the flow follows the active system light/dark appearance.
- Added new mock-screen drawables for gauge and graph previews.

- Onboarding v2: graph preview spacing fixed; previews rebuilt closer to real home/graph/settings UI, with actual gauge-based home/trip sections and preference-style settings screens.

- onboarding pager: replaced overlapping custom transformer with clean MarginPageTransformer spacing
- onboarding pages: initial viewport now opens at the bottom/description block instead of the top preview

- Onboarding previews aligned strictly with real settings: notifications/trip/recording/data/theme slides now use actual category and preference labels/summaries from root_preferences and SettingsFragment visibility rules.

- Removed fake Barometer notification card from onboarding slide 3 (notifications preview).

- Added a one-time no-barometer startup dialog in `MainActivity` with the message that measurements and history are unavailable on devices without a pressure sensor.
