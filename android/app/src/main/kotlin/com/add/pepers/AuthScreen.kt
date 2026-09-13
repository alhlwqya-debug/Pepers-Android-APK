package com.add.pepers

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun AuthScreen(onAuthenticated: () -> Unit) {
    val context = LocalContext.current
    var register by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    fun submit() {
        error = ""
        if (email.trim().isBlank() || password.isBlank()) { error = "البريد الإلكتروني وكلمة المرور مطلوبان"; return }
        if (register && name.trim().isBlank()) { error = "الاسم مطلوب"; return }
        if (register && password != confirmPassword) { error = "كلمتا المرور غير متطابقتين"; return }
        if (password.length < 6) { error = "كلمة المرور يجب أن تكون 6 أحرف على الأقل"; return }
        busy = true
        CoroutineScope(Dispatchers.IO).launch {
            val result = if (register) SupabaseAuth.signUp(context, name, phone, email, password)
            else SupabaseAuth.signIn(context, email, password)
            withContext(Dispatchers.Main) {
                busy = false
                result.onSuccess { session ->
                    if (register && session == null) {
                        error = "تم إنشاء الحساب، لكن Supabase يطلب تأكيد البريد. عطّل Confirm email في إعدادات Authentication ليصبح الدخول مباشرًا بدون OTP."
                    } else onAuthenticated()
                }.onFailure { error = it.message ?: "حدث خطأ غير متوقع" }
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(PageBg).verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Pepers", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Purple)
        Spacer(Modifier.height(8.dp))
        Text(if (register) "إنشاء حساب جديد" else "تسجيل الدخول", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(24.dp))

        if (register) {
            OutlinedTextField(name, { name = it }, label = { Text("الاسم الكامل") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(phone, { phone = it }, label = { Text("رقم الهاتف") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
        }
        OutlinedTextField(email, { email = it }, label = { Text("البريد الإلكتروني") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(password, { password = it }, label = { Text("كلمة المرور") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        if (register) {
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(confirmPassword, { confirmPassword = it }, label = { Text("تأكيد كلمة المرور") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(16.dp))
        if (error.isNotBlank()) Text(error, color = Red, fontSize = 12.sp, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        Button(onClick = { submit() }, enabled = !busy, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Green)) {
            Text(if (busy) "جارٍ المعالجة..." else if (register) "إنشاء الحساب" else "دخول")
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { SupabaseAuth.beginGoogleLogin(context) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4285F4))
        ) { Text("المتابعة باستخدام Google") }
        TextButton(enabled = !busy, onClick = { register = !register; error = "" }) {
            Text(if (register) "لدي حساب بالفعل — تسجيل الدخول" else "ليس لدي حساب — إنشاء حساب")
        }
    }
}
