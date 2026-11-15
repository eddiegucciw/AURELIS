
package com.aurelis.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Midnight = Color(0xFF050A14)
private val Navy = Color(0xFF0C1525)
private val Cyan = Color(0xFF00D9FF)
private val Blue = Color(0xFF167BFF)
private val Amber = Color(0xFFFFB52E)
private val Silver = Color(0xFFC7D2DC)
private val SoftWhite = Color(0xFFD5E8F5)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            AurelisApp()
        }
    }
}

@Composable
fun AurelisApp() {
    var selectedTab by remember { mutableIntStateOf(0) }

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Midnight,
            surface = Navy,
            primary = Cyan,
            secondary = Amber
        )
    ) {
        Scaffold(
            containerColor = Midnight,
            bottomBar = {
                NavigationBar(
                    containerColor = Navy
                ) {
                    val items = listOf(
                        "Home",
                        "Intelligence",
                        "Memory",
                        "Commands",
                        "Settings"
                    )

                    val icons = listOf(
                        Icons.Default.Home,
                        Icons.Default.AutoAwesome,
                        Icons.Default.Psychology,
                        Icons.Default.Bolt,
                        Icons.Default.Settings
                    )

                    items.forEachIndexed { index, item ->
                        NavigationBarItem(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            icon = {
                                Icon(
                                    icons[index],
                                    contentDescription = item
                                )
                            },
                            label = { Text(item, fontSize = 9.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Cyan,
                                selectedTextColor = Cyan,
                                indicatorColor = Blue.copy(alpha = 0.18f),
                                unselectedIconColor = Silver.copy(alpha = 0.6f),
                                unselectedTextColor = Silver.copy(alpha = 0.6f)
                            )
                        )
                    }
                }
            }
        ) { padding ->

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                when (selectedTab) {
                    0 -> HomeScreen()
                    1 -> FeatureScreen(
                        "INTELLIGENCE",
                        "Your conversational intelligence centre.",
                        Icons.Default.AutoAwesome
                    )
                    2 -> FeatureScreen(
                        "MEMORY",
                        "Your personal knowledge and memory system.",
                        Icons.Default.Psychology
                    )
                    3 -> FeatureScreen(
                        "COMMAND CENTRE",
                        "Manage reminders, tasks and supported actions.",
                        Icons.Default.Bolt
                    )
                    4 -> FeatureScreen(
                        "SETTINGS & PRIVACY",
                        "Configure your AURELIS experience.",
                        Icons.Default.Settings
                    )
                }
            }
        }
    }
}

@Composable
fun HomeScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Midnight)
            .padding(horizontal = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(28.dp))

        Text(
            text = "A U R E L I S",
            color = SoftWhite,
            fontSize = 23.sp,
            fontWeight = FontWeight.Light,
            letterSpacing = 5.sp
        )

        Text(
            text = "YOUR INTELLIGENCE. ALWAYS.",
            color = Cyan.copy(alpha = 0.75f),
            fontSize = 9.sp,
            letterSpacing = 3.sp
        )

        Spacer(Modifier.height(22.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .background(Cyan, CircleShape)
            )

            Spacer(Modifier.width(8.dp))

            Text(
                "SYSTEM INITIALIZED",
                color = Cyan,
                fontSize = 10.sp,
                letterSpacing = 2.sp
            )
        }

        Spacer(Modifier.height(20.dp))

        AurelisCore(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )

        Text(
            text = "AURELIS IS READY",
            color = SoftWhite,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 2.sp
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Foundation mode • Voice activation not yet enabled",
            color = Silver.copy(alpha = 0.6f),
            fontSize = 10.sp
        )

        Spacer(Modifier.height(22.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = Navy
            ),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(Modifier.padding(18.dp)) {
                Text(
                    "SYSTEM STATUS",
                    color = Cyan,
                    fontSize = 11.sp,
                    letterSpacing = 2.sp
                )

                Spacer(Modifier.height(14.dp))

                StatusRow("Interface", "Operational")
                StatusRow("Cloud intelligence", "Not configured")
                StatusRow("Offline intelligence", "Not installed")
                StatusRow("Wake-word engine", "Not installed")
                StatusRow("Personal memory", "Coming soon")
            }
        }

        Spacer(Modifier.height(18.dp))

        Text(
            text = "UNDERSTAND BEFORE ACTING.",
            color = Amber.copy(alpha = 0.8f),
            fontSize = 10.sp,
            letterSpacing = 2.sp
        )

        Spacer(Modifier.height(18.dp))
    }
}

@Composable
fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            color = Silver.copy(alpha = 0.7f),
            fontSize = 12.sp
        )

        Text(
            value,
            color = SoftWhite,
            fontSize = 11.sp
        )
    }
}

@Composable
fun AurelisCore(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "core")

    val pulse by transition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 2400,
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 18000,
                easing = androidx.compose.animation.core.LinearEasing
            )
        ),
        label = "rotation"
    )

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
        ) {
            val center = this.center
            val radius = size.minDimension * 0.36f

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Blue.copy(alpha = 0.18f),
                        Cyan.copy(alpha = 0.07f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = radius * 1.9f
                ),
                radius = radius * 1.9f,
                center = center
            )

            drawCircle(
                color = Blue.copy(alpha = 0.3f),
                radius = radius * pulse,
                center = center,
                style = Stroke(width = 2.dp.toPx())
            )

            drawCircle(
                color = Cyan.copy(alpha = 0.8f),
                radius = radius * 0.91f,
                center = center,
                style = Stroke(width = 3.dp.toPx())
            )

            drawCircle(
                color = Silver.copy(alpha = 0.75f),
                radius = radius * 0.78f,
                center = center,
                style = Stroke(width = 7.dp.toPx())
            )

            drawArc(
                color = Amber,
                startAngle = rotation,
                sweepAngle = 65f,
                useCenter = false,
                topLeft = Offset(
                    center.x - radius * 0.7f,
                    center.y - radius * 0.7f
                ),
                size = androidx.compose.ui.geometry.Size(
                    radius * 1.4f,
                    radius * 1.4f
                ),
                style = Stroke(width = 5.dp.toPx())
            )

            drawArc(
                color = Cyan,
                startAngle = -rotation,
                sweepAngle = 115f,
                useCenter = false,
                topLeft = Offset(
                    center.x - radius * 0.64f,
                    center.y - radius * 0.64f
                ),
                size = androidx.compose.ui.geometry.Size(
                    radius * 1.28f,
                    radius * 1.28f
                ),
                style = Stroke(width = 4.dp.toPx())
            )

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF203C59),
                        Color(0xFF0A192C),
                        Midnight
                    ),
                    center = center,
                    radius = radius * 0.65f
                ),
                radius = radius * 0.64f,
                center = center
            )

            drawCircle(
                color = Cyan.copy(alpha = 0.8f),
                radius = radius * 0.64f,
                center = center,
                style = Stroke(width = 2.dp.toPx())
            )
        }

        Text(
            text = "A",
            color = Silver,
            fontSize = 76.sp,
            fontWeight = FontWeight.ExtraLight
        )

        Text(
            text = "CORE",
            modifier = Modifier.offset(y = 58.dp),
            color = Cyan,
            fontSize = 9.sp,
            letterSpacing = 4.sp
        )
    }
}

@Composable
fun FeatureScreen(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Midnight)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Cyan,
            modifier = Modifier.size(64.dp)
        )

        Spacer(Modifier.height(24.dp))

        Text(
            title,
            color = SoftWhite,
            fontSize = 20.sp,
            letterSpacing = 2.sp,
            fontWeight = FontWeight.Medium
        )

        Spacer(Modifier.height(12.dp))

        Text(
            description,
            color = Silver.copy(alpha = 0.7f),
            fontSize = 13.sp
        )

        Spacer(Modifier.height(20.dp))

        Text(
            "MODULE UNDER DEVELOPMENT",
            color = Amber,
            fontSize = 10.sp,
            letterSpacing = 2.sp
        )
    }
}
