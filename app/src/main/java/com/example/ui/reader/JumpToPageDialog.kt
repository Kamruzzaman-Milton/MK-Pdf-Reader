package com.example.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R

@Composable
fun JumpToPageDialog(
    currentPage: Int, // 1-indexed
    totalPages: Int,
    onDismiss: () -> Unit,
    onJumpToPage: (Int) -> Unit
) {
    var pageInput by remember { mutableStateOf(currentPage.toString()) }
    val enteredNum = pageInput.toIntOrNull()
    val isValid = enteredNum != null && enteredNum in 1..totalPages

    fun confirmJump() {
        if (isValid && enteredNum != null) {
            onJumpToPage(enteredNum)
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.jump_to_page),
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.enter_page_number, totalPages),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    IconButton(
                        onClick = {
                            val current = pageInput.toIntOrNull() ?: currentPage
                            if (current > 1) pageInput = (current - 1).toString()
                        },
                        enabled = (pageInput.toIntOrNull() ?: currentPage) > 1,
                        modifier = Modifier.testTag("jump_decrement_btn")
                    ) {
                        Icon(Icons.Default.Remove, contentDescription = "Previous page")
                    }

                    OutlinedTextField(
                        value = pageInput,
                        onValueChange = { input ->
                            val filtered = input.filter { it.isDigit() }
                            pageInput = filtered
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Go
                        ),
                        keyboardActions = KeyboardActions(
                            onGo = { confirmJump() }
                        ),
                        singleLine = true,
                        modifier = Modifier
                            .width(110.dp)
                            .testTag("page_number_input"),
                        shape = RoundedCornerShape(12.dp),
                        isError = pageInput.isNotEmpty() && !isValid
                    )

                    IconButton(
                        onClick = {
                            val current = pageInput.toIntOrNull() ?: currentPage
                            if (current < totalPages) pageInput = (current + 1).toString()
                        },
                        enabled = (pageInput.toIntOrNull() ?: currentPage) < totalPages,
                        modifier = Modifier.testTag("jump_increment_btn")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Next page")
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Quick step chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    FilledTonalButton(
                        onClick = { pageInput = "1" },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("1", fontSize = 12.sp)
                    }

                    if (totalPages >= 10) {
                        FilledTonalButton(
                            onClick = {
                                val cur = pageInput.toIntOrNull() ?: currentPage
                                pageInput = (cur - 10).coerceAtLeast(1).toString()
                            },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("-10", fontSize = 12.sp)
                        }

                        FilledTonalButton(
                            onClick = {
                                val cur = pageInput.toIntOrNull() ?: currentPage
                                pageInput = (cur + 10).coerceAtMost(totalPages).toString()
                            },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("+10", fontSize = 12.sp)
                        }
                    }

                    FilledTonalButton(
                        onClick = { pageInput = totalPages.toString() },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("$totalPages", fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { confirmJump() },
                enabled = isValid,
                modifier = Modifier.testTag("jump_confirm_btn")
            ) {
                Text(stringResource(R.string.go))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("jump_cancel_btn")
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
