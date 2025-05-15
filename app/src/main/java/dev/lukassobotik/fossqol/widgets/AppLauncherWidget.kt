package dev.lukassobotik.fossqol.widgets

import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import dev.lukassobotik.fossqol.R

class AppLauncherWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: android.content.Context, id: GlanceId) {
        provideContent {
            GlanceTheme {
                WidgetContent(context)
            }
        }
    }

    @Composable
    private fun WidgetContent(context: android.content.Context) {
        val packageName = context.packageName
        val appName = try {
            val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            "App"
        }

        val mainActivityClass = "$packageName.MainActivity"
        val componentName = ComponentName(packageName, mainActivityClass)

        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .padding(8.dp)
                .clickable(actionStartActivity(componentName)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                provider = ImageProvider(R.mipmap.ic_launcher),
                contentDescription = "App Icon",
                modifier = GlanceModifier.size(48.dp)
            )

            Spacer(modifier = GlanceModifier.height(4.dp))

            Text(
                text = appName,
                style = TextStyle(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    color = GlanceTheme.colors.onBackground
                ),
                maxLines = 1
            )
        }
    }
}

class AppLauncherWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AppLauncherWidget()
}