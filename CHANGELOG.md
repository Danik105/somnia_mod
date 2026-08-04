# Changelog - Somnium Mod

## [Unreleased] - 2026-07-23

### 🐛 Исправлено

#### 1. Баг дублирования дверей при повторном входе в сон
**Проблема:** При повторном входе в один и тот же сон старые маркеры "Двери пробуждения" (ArmorStand) и собираемые предметы (ItemEntity) не удалялись, накапливались в измерении, и игрок мог спавниться очень близко к старой двери.

**Решение:** Добавлен метод `cleanupDreamEntities()` в `DreamManager`, который вызывается при пробуждении игрока и удаляет:
- Маркеры дверей пробуждения (ArmorStand с флагами invulnerable + invisible)
- Собираемые предметы с флагом neverDespawn
- Оставшихся монстров сна (AbstractNightmareEntity)

**Файл:** `src/main/java/com/somnium/mod/dream/DreamManager.java`
- Добавлен метод `cleanupDreamEntities(ServerWorld, ActiveDream)`
- Изменён метод `wake()` - добавлен вызов очистки перед телепортацией игрока

---

#### 2. Спавн ванильных мобов в измерениях снов
**Проблема:** В измерениях снов могли спавниться обычные minecraft мобы (зомби, скелеты, криперы и т.д.), что нарушало атмосферу кошмаров.

**Решение:** Добавлены `spawn_overrides` во все 7 dimension JSON файлов с `probability: 0.0` для всех категорий:
- creature (пассивные животные)
- monster (враждебные мобы)
- ambient (летучие мыши и т.д.)
- water_creature, water_ambient, underground_water_creature
- axolotls

**Файлы изменены:**
- `src/main/resources/data/somnium/dimension/dream_drowning_city.json`
- `src/main/resources/data/somnium/dimension/dream_shadow_forest.json`
- `src/main/resources/data/somnium/dimension/dream_mirror_wastes.json`
- `src/main/resources/data/somnium/dimension/dream_collapsing_mine.json`
- `src/main/resources/data/somnium/dimension/dream_void_of_eyes.json`
- `src/main/resources/data/somnium/dimension/dream_crimson_feast.json`
- `src/main/resources/data/somnium/dimension/dream_within_dream.json`

---

### ✨ Добавлено

#### 3. Сохранение состояния здоровья и сытости
**Проблема:** После пробуждения сохранялось состояние из сна - урон и голод, полученные во сне, переносились в реальность.

**Решение:** 
- Расширена запись `ActiveDream` тремя новыми полями: `savedHealth`, `savedFoodLevel`, `savedSaturation`
- При входе в сон (`enterDream`) сохраняется текущее состояние игрока
- При пробуждении (`wake`) состояние восстанавливается полностью

**Файл:** `src/main/java/com/somnium/mod/dream/DreamManager.java`
- Изменена структура `ActiveDream` record
- Изменён метод `enterDream()` - добавлено сохранение health/food/saturation
- Изменён метод `wake()` - добавлено восстановление состояния через `setHealth()`, `setFoodLevel()`, `setSaturationLevel()`

---

#### 4. Негативные эффекты во время сна (уникальные для каждого сна)
**Описание:** Теперь во время нахождения в сне на игрока накладываются дебаффы. Каждый сон имеет свой уникальный набор эффектов:

**Эффекты по снам:**
- **Тонущий город**: Slowness III (тяжело двигаться в воде) + Mining Fatigue V
- **Лес теней**: Blindness + Slowness II (темнота и дезориентация) + Mining Fatigue V
- **Пустошь зеркал**: Nausea + Weakness II (искажение восприятия) + Mining Fatigue V
- **Обрушающаяся шахта**: Blindness III (почти полная темнота) + Mining Fatigue V
- **Кровавый пир**: Hunger III + Weakness (голод и слабость) + Mining Fatigue V
- **Пустота с глазами**: Slow Falling + Slowness II (парение в пустоте) + Mining Fatigue V
- **Сон-в-сне**: Все эффекты сразу (самый тяжёлый кошмар)

Длительность эффектов = длительность сна, они автоматически снимаются при пробуждении.

**Файл:** `src/main/java/com/somnium/mod/dream/DreamManager.java`
- Переписан метод `applyDreamDebuffs(ServerPlayerEntity, Identifier, long)` с поддержкой уникальных эффектов
- Изменён метод `enterDream()` - добавлена передача dreamId в applyDreamDebuffs()

---

#### 5. Настройка целей снов - убраны двери не из всех снов
**Описание:** Не все сны теперь имеют "Дверь пробуждения" как цель. Некоторые завершаются только по выполнению специфической цели или таймауту.

**Изменения по снам:**
- ✅ **Тонущий город** - оставлена дверь (воздушный карман)
- ✅ **Лес теней** - оставлена дверь (Дерево-Маяк)
- ✅ **Пустошь зеркал** - BOSS_KILL (без изменений)
- ❌ **Обрушающаяся шахта** - УБРАНА дверь, теперь только таймаут (логично - путь обрушивается)
- ✅ **Кровавый пир** - COLLECT_ITEMS (без изменений)
- ✅ **Пустота с глазами** - оставлена дверь (портал пробуждения)
- ✅ **Сон-в-сне** - BOSS_KILL (без изменений)

**Файл:** `src/main/java/com/somnium/mod/dream/DreamRegistry.java`
- Изменён метод `jsonDefaults()` - обновлены цели снов
- Изменён `DreamManager.enterDream()` - добавлена проверка на null для objectiveType

---

## Технические детали

### Совместимость
- Все изменения совместимы с Fabric 1.21.11
- Не требуется миграция существующих сохранений (новые поля в `ActiveDream` хранятся только в памяти)

### Тестирование
После сборки рекомендуется протестировать:
1. Повторный вход в один и тот же сон (проверить отсутствие дублей маркеров)
2. Проверку отсутствия vanilla мобов в измерениях снов (особенно shadow_forest с `features: true`)
3. Получение урона во сне и проверку, что здоровье восстанавливается при пробуждении
4. Наличие уникальных эффектов в каждом сне
5. Сон "Обрушающаяся шахта" не должен иметь двери выхода

### Известные ограничения
- `cleanupDreamEntities()` работает только для последнего покинувшего сон игрока (мультиплеер: если два игрока в одном сне, первый проснувшийся не очищает мир)

---

## Следующие задачи (из TODO.md)

### В разработке:
- [ ] Создать нового моба StalkerEntity (Weeping Angel механика)
- [ ] Создать новый сон "Эффект зеркала" с NPC-двойником
- [ ] Создать новый сон "Падающие доски"

