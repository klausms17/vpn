package com.klausms.vpn.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.klausms.vpn.R
import com.klausms.vpn.data.AccountStatus
import com.klausms.vpn.ui.AccountView
import com.klausms.vpn.ui.MainViewModel
import com.klausms.vpn.ui.components.IconTile
import com.klausms.vpn.ui.components.InsetGroup
import com.klausms.vpn.ui.components.IosAlert
import com.klausms.vpn.ui.components.ListRow
import com.klausms.vpn.ui.components.NavBar
import com.klausms.vpn.ui.components.PrimaryButton
import com.klausms.vpn.ui.components.RowDivider
import com.klausms.vpn.ui.components.SecondaryButton
import com.klausms.vpn.ui.components.SectionFooter
import com.klausms.vpn.ui.components.SectionHeader
import com.klausms.vpn.ui.components.TextAction
import com.klausms.vpn.ui.components.navBarClearance
import com.klausms.vpn.ui.theme.IosType
import com.klausms.vpn.ui.theme.kc
import kotlinx.coroutines.delay

/** What the account screen asks of the account; each runs in the background. */
class AccountActions(
    val login: (email: String, password: String) -> Unit,
    val register: (email: String, password: String) -> Unit,
    val forgot: (email: String) -> Unit,
    val resend: () -> Unit,
    val logout: () -> Unit,
    val delete: (password: String) -> Unit,
    val check: () -> Unit,
)

/** The account (docs/accounts/PLAN.md): optional, for the servers on every device. */
@Composable
fun AccountScreen(vm: MainViewModel, onBack: () -> Unit) {
    val view by vm.accountView.collectAsStateWithLifecycle()
    // While the owner decides, the screen asks now and then; the check
    // itself keeps to every 5 minutes.
    LaunchedEffect(view.status) {
        if (view.status != AccountStatus.PENDING) return@LaunchedEffect
        while (true) {
            delay(60_000)
            vm.account { checkIfDue() }
        }
    }
    AccountContent(
        view = view,
        actions = AccountActions(
            login = { e, p -> vm.account { login(e, p) } },
            register = { e, p -> vm.account { register(e, p) } },
            forgot = { e -> vm.account { forgot(e) } },
            resend = { vm.account { resend() } },
            logout = { vm.account { logout() } },
            delete = { p -> vm.account { delete(p) } },
            check = { vm.account { check() } },
        ),
        onBack = onBack,
    )
}

@Composable
fun AccountContent(view: AccountView, actions: AccountActions, onBack: () -> Unit) {
    var email by rememberSaveable { mutableStateOf("") }
    // Never saved with the screen's state, and gone after every request.
    var password by remember { mutableStateOf("") }
    var askLogout by remember { mutableStateOf(false) }
    var askDelete by remember { mutableStateOf(false) }
    LaunchedEffect(view.busy, view.status) {
        if (!view.busy) password = ""
    }

    Column(Modifier.fillMaxSize().background(kc.page)) {
        NavBar("Аккаунт", onBack)
        // Above the keyboard: the form scrolls, with room for it at the end.
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(bottom = navBarClearance() + 24.dp),
        ) {
            when (view.status) {
                AccountStatus.SIGNED_OUT -> SignedOut(view, email, { email = it }, password, { password = it }, actions)
                AccountStatus.UNCONFIRMED -> Unconfirmed(view, password, { password = it }, actions)
                else -> SignedIn(view, actions, onLogout = { askLogout = true }, onDelete = { askDelete = true })
            }
        }
    }

    if (askLogout) {
        IosAlert(
            title = "Выйти из аккаунта?",
            text = "Серверы аккаунта будут убраны с телефона. Войти снова можно в любой момент.",
            onDismiss = { askLogout = false },
            confirm = "Выйти",
            onConfirm = { askLogout = false; actions.logout() },
        )
    }
    if (askDelete) {
        var confirmPassword by remember { mutableStateOf("") }
        IosAlert(
            title = "Удалить аккаунт?",
            onDismiss = { askDelete = false },
            confirm = "Удалить",
            onConfirm = { askDelete = false; actions.delete(confirmPassword) },
            destructive = true,
            confirmEnabled = confirmPassword.isNotEmpty(),
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Аккаунт удалится насовсем, а его серверы пропадут со всех устройств. Введите пароль, чтобы подтвердить.",
                        style = IosType.subhead,
                        color = kc.secondary,
                    )
                    Field(confirmPassword, { confirmPassword = it }, "Пароль", secret = true, filled = true)
                }
            },
        )
    }
}

/** What an account gives, as the owner asked: in plain words. */
@Composable
private fun Why() {
    Text(
        "С аккаунтом VPN работает на всех ваших устройствах: телефоне, компьютере, iPhone и Mac. Войдите с одной " +
            "почтой, и серверы появятся сами, без ссылок и ключей. Забыли пароль — восстановите его по почте. " +
            "Без аккаунта всё работает как раньше.",
        style = IosType.subhead,
        color = kc.secondary,
        modifier = Modifier.padding(start = 36.dp, end = 36.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun SignedOut(
    view: AccountView,
    email: String,
    onEmail: (String) -> Unit,
    password: String,
    onPassword: (String) -> Unit,
    actions: AccountActions,
) {
    Why()
    SectionHeader("Почта и пароль")
    InsetGroup {
        Field(email, onEmail, "Почта", secret = false, imeAction = ImeAction.Next)
        RowDivider()
        Field(password, onPassword, "Пароль", secret = true, onDone = { actions.login(email, password) })
    }
    Note(view, hint = "Новый пароль — не короче 8 символов.")
    Buttons {
        PrimaryButton(if (view.busy) "Минуту…" else "Войти", { actions.login(email, password) }, enabled = !view.busy)
        SecondaryButton("Создать аккаунт", { actions.register(email, password) })
        TextAction("Забыли пароль?", { actions.forgot(email) }, enabled = !view.busy)
    }
}

@Composable
private fun Unconfirmed(view: AccountView, password: String, onPassword: (String) -> Unit, actions: AccountActions) {
    Text(
        "Подтвердите почту",
        style = IosType.title3,
        color = kc.label,
        modifier = Modifier.padding(start = 36.dp, end = 36.dp, top = 20.dp),
    )
    Text(
        "Мы отправили вам письмо для подтверждения на ${view.email}. Пожалуйста, завершите регистрацию, пройдя по ссылке из письма.",
        style = IosType.body,
        color = kc.label,
        modifier = Modifier.padding(start = 36.dp, end = 36.dp, top = 8.dp),
    )
    SectionHeader("Подтвердили? Введите пароль, чтобы войти")
    InsetGroup {
        Field(password, onPassword, "Пароль", secret = true, onDone = { actions.login(view.email, password) })
    }
    Note(view, hint = "Письма нет? Загляните в папку «Спам».")
    Buttons {
        PrimaryButton(if (view.busy) "Минуту…" else "Войти", { actions.login(view.email, password) }, enabled = !view.busy)
        SecondaryButton("Отправить письмо ещё раз", actions.resend)
        TextAction("Другая почта", actions.logout, enabled = !view.busy)
    }
}

@Composable
private fun SignedIn(view: AccountView, actions: AccountActions, onLogout: () -> Unit, onDelete: () -> Unit) {
    Why()
    SectionHeader("Вы вошли")
    InsetGroup {
        ListRow(
            title = view.email,
            subtitle = when (view.status) {
                AccountStatus.ACTIVE -> "Доступ есть"
                AccountStatus.REJECTED -> "Доступ не выдан"
                else -> "Ждёт доступа"
            },
            leading = { IconTile(R.drawable.ic_person_ios, kc.blue) },
        )
    }
    Note(
        view,
        hint = when (view.status) {
            AccountStatus.ACTIVE -> "Серверы аккаунта — в списке серверов, они обновляются сами."
            AccountStatus.REJECTED -> "Доступ не выдан. Если это ошибка, напишите тому, кто дал вам Kirov VPN."
            else -> "Ждём, когда вам откроют доступ. Мы пришлём письмо, а серверы появятся в приложении сами."
        },
    )
    if (view.status != AccountStatus.ACTIVE) {
        Buttons {
            SecondaryButton(if (view.busy) "Проверяем…" else "Проверить сейчас", actions.check)
        }
    }
    Spacer(Modifier.height(28.dp))
    InsetGroup {
        ListRow(title = "Выйти", titleColor = kc.blue, onClick = onLogout)
        RowDivider()
        ListRow(title = "Удалить аккаунт", titleColor = kc.red, onClick = onDelete)
    }
}

/** Under a group: [hint], which always stays, then what the last request said. */
@Composable
private fun Note(view: AccountView, hint: String) {
    SectionFooter(hint)
    view.note?.let {
        Text(
            it,
            style = IosType.footnote,
            color = if (view.noteIsError) kc.red else kc.green,
            modifier = Modifier.padding(start = 36.dp, end = 36.dp, top = 7.dp),
        )
    }
}

@Composable
private fun Buttons(content: @Composable () -> Unit) {
    Column(
        Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

/** One line of the form, in a group or, [filled], on its own. */
@Composable
private fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    secret: Boolean,
    imeAction: ImeAction = ImeAction.Done,
    onDone: () -> Unit = {},
    filled: Boolean = false,
) {
    BasicTextField(
        value = value,
        // The service's limits; a longer paste is cut, not refused.
        onValueChange = { onValueChange(it.take(if (secret) 1024 else 254)) },
        singleLine = true,
        textStyle = IosType.body.copy(color = kc.label),
        cursorBrush = SolidColor(kc.green),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            autoCorrectEnabled = false,
            keyboardType = if (secret) KeyboardType.Password else KeyboardType.Email,
            imeAction = imeAction,
        ),
        keyboardActions = if (imeAction == ImeAction.Done) KeyboardActions(onDone = { onDone() }) else KeyboardActions.Default,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (filled) Modifier.clip(RoundedCornerShape(12.dp)).background(kc.fill) else Modifier)
            .heightIn(min = 44.dp)
            .padding(horizontal = if (filled) 12.dp else 16.dp, vertical = 11.dp),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Text(placeholder, style = IosType.body, color = kc.tertiary)
                inner()
            }
        },
    )
}
