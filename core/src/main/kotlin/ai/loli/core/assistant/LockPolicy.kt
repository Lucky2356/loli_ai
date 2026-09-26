package ai.loli.core.assistant

/**
 * Что ассистенту можно делать, пока телефон заблокирован. Настраивается пользователем;
 * по умолчанию — записывать новое, таймеры/будильники и звонки, но не показывать и не менять личные данные
 * и не открывать приложения.
 */
data class LockPolicy(
    /** Новые заметки, расходы, задачи, напоминания, «запомни», дописать в список. */
    val create: Boolean = true,
    /** Таймер, будильник, фонарик, музыка, громкость, заряд, «не беспокоить». */
    val basicDevice: Boolean = true,
    /** Звонки и сообщения. */
    val calls: Boolean = true,
    /** Показывать записи: расходы, задачи, память, поиск, план на день. */
    val view: Boolean = false,
    /** Изменять и удалять записи. */
    val edit: Boolean = false,
    /** Открывать приложения, сайты, камеру, настройки. */
    val apps: Boolean = false,
) {
    fun allows(action: AssistantAction): Boolean = when (action) {
        is AssistantAction.CreateNote, is AssistantAction.CreateExpense, is AssistantAction.CreateTask,
        is AssistantAction.CreateReminder, is AssistantAction.Remember, is AssistantAction.AppendNote,
        is AssistantAction.AddToList, is AssistantAction.AddBirthday, is AssistantAction.CreateSecretNote -> create
        is AssistantAction.Clarify -> true
        is AssistantAction.QueryList, is AssistantAction.QueryBirthdays, AssistantAction.QueryRoutines -> view
        is AssistantAction.QueryExpenses, is AssistantAction.QueryTasks, AssistantAction.QueryReminders,
        is AssistantAction.QueryMemories, is AssistantAction.Search, is AssistantAction.Agenda -> view
        is AssistantAction.Device -> when (val c = action.command) {
            is DeviceCommand.Timer, is DeviceCommand.Alarm, is DeviceCommand.Flashlight, DeviceCommand.Battery,
            is DeviceCommand.Media, is DeviceCommand.Volume, is DeviceCommand.DoNotDisturb, is DeviceCommand.Brightness -> basicDevice
            // Заблокировать экран можно всегда; остальные системные кнопки — как открытие приложений.
            is DeviceCommand.Global -> c.action == GlobalAction.LOCK || apps
            is DeviceCommand.Call, is DeviceCommand.Message -> calls
            else -> apps
        }
        else -> edit
    }
}
