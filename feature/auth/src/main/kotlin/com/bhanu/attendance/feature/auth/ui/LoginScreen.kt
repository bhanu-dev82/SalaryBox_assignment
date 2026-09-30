package com.bhanu.attendance.feature.auth.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bhanu.attendance.core.designsystem.accessibility.semanticHeading
import com.bhanu.attendance.core.designsystem.theme.BhanuTheme
import com.bhanu.attendance.domain.model.Role
import com.bhanu.attendance.feature.auth.R
import com.bhanu.attendance.feature.auth.state.LoginEvent
import com.bhanu.attendance.feature.auth.state.LoginViewModel

/**
 * Sign-in screen.
 *
 * Collects state with `collectAsStateWithLifecycle`, so collection stops when the screen is
 * not visible instead of running behind it.
 */
@Composable
fun LoginRoute(
    onSignedIn: (Role) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LoginViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is LoginEvent.SignedIn -> onSignedIn(event.role)
            }
        }
    }

    LoginScreen(
        state = state,
        onRoleChange = viewModel::onRoleChange,
        onEmployeeIdChange = viewModel::onEmployeeIdChange,
        onPinChange = viewModel::onPinChange,
        onSubmit = viewModel::submit,
        onDismissError = viewModel::dismissError,
        modifier = modifier,
    )
}

@Composable
fun LoginScreen(
    state: com.bhanu.attendance.feature.auth.state.LoginUiState,
    onRoleChange: (Role) -> Unit,
    onEmployeeIdChange: (String) -> Unit,
    onPinChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // rememberSaveable, not remember: the employee ID and PIN survive rotation, which matters
    // on a tablet where the window size class change recreates the Activity.
    var pinVisible by rememberSaveable { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                // Never let the keyboard cover the PIN field, and respect the gesture bar.
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Capped so the form stays readable on a compact phone and does not stretch
            // absurdly wide on a tablet.
            Column(
                modifier = Modifier.widthIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.auth_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.semanticHeading(),
                )
                Text(
                    text = stringResource(R.string.auth_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                RoleSelector(
                    selected = state.role,
                    onSelect = onRoleChange,
                    enabled = !state.isSubmitting,
                )

                if (state.showEmployeeId) {
                    OutlinedTextField(
                        value = state.employeeId,
                        onValueChange = onEmployeeIdChange,
                        label = { Text(stringResource(R.string.auth_employee_id)) },
                        singleLine = true,
                        enabled = !state.isSubmitting,
                        // autocapitalize off: employee IDs are upper case and re-typing them in
                        // lower case would be a needless rejection.
                        keyboardOptions = KeyboardOptions(
                            capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.None,
                            imeAction = ImeAction.Next,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("login_employee_id"),
                    )
                }

                OutlinedTextField(
                    value = state.pin,
                    onValueChange = onPinChange,
                    label = { Text(stringResource(R.string.auth_pin)) },
                    singleLine = true,
                    enabled = !state.isSubmitting,
                    // NumberPassword rather than Password: it brings up the numeric keypad on
                    // Android, which is what a PIN entry wants.
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Done,
                    ),
                    visualTransformation = if (pinVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { pinVisible = !pinVisible }) {
                            Icon(
                                imageVector = if (pinVisible) {
                                    Icons.Filled.VisibilityOff
                                } else {
                                    Icons.Filled.Visibility
                                },
                                contentDescription = if (pinVisible) "Hide PIN" else "Show PIN",
                            )
                        }
                    },
                    keyboardActions = KeyboardActions(
                        onDone = {
                            keyboard?.hide()
                            onSubmit()
                        },
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("login_pin"),
                )

                if (state.errorMessage != null) {
                    Text(
                        text = state.errorMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("login_error"),
                    )
                    TextButton(onClick = onDismissError) { Text("Dismiss") }
                }

                Button(
                    onClick = {
                        keyboard?.hide()
                        onSubmit()
                    },
                    enabled = state.canSubmit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("login_submit"),
                ) {
                    if (state.isSubmitting) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .height(20.dp)
                                .semantics { contentDescription = "Signing in" },
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Login,
                            contentDescription = null,
                            modifier = Modifier.height(20.dp),
                        )
                        Spacer(Modifier.widthIn(min = 8.dp))
                        Text(stringResource(R.string.auth_sign_in))
                    }
                }

                DemoCredentialsHint()
            }
        }
    }
}

@Composable
private fun RoleSelector(
    selected: Role,
    onSelect: (Role) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        Role.entries.forEachIndexed { index, role ->
            SegmentedButton(
                selected = selected == role,
                onClick = { onSelect(role) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index, Role.entries.size),
                modifier = Modifier.testTag("login_role_${role.name.lowercase()}"),
            ) {
                Text(
                    text = stringResource(
                        if (role == Role.ADMIN) R.string.auth_role_admin else R.string.auth_role_staff
                    )
                )
            }
        }
    }
}

/**
 * Demo credentials, shown on the login screen.
 *
 * Shown deliberately: the assignment asks for demo credentials, and making a reviewer type
 * `1234` from a README is a small, avoidable friction. These are demo values, not secrets —
 * every PIN in the app is hashed with the same PBKDF2 path.
 */
@Composable
private fun DemoCredentialsHint(modifier: Modifier = Modifier) {
    val status = BhanuTheme.status
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription =
                    "Demo credentials. Admin: PIN 1234. Staff: employee ID EMP001 or EMP002, PIN 1111."
            },
        color = status.warningContainer,
        contentColor = status.onWarningContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.auth_demo_credentials),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = "Admin — PIN 1234",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "Staff — EMP001 or EMP002, PIN 1111",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
