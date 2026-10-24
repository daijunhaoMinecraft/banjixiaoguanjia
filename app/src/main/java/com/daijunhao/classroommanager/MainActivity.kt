package com.daijunhao.classroommanager

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.daijunhao.classroommanager.ui.theme.ClassroomManagerTheme
import com.daijunhao.classroommanager.ui.LoginScreen
import com.daijunhao.classroommanager.data.TokenStore
import com.daijunhao.classroommanager.data.ClassApi
import com.daijunhao.classroommanager.data.ClassInfo
import coil.compose.AsyncImage
import com.daijunhao.classroommanager.data.MemberInfo
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.ui.draw.clip
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextOverflow
import com.daijunhao.classroommanager.data.HomeApi
import com.daijunhao.classroommanager.data.HomeItem
import kotlinx.coroutines.launch
import com.daijunhao.classroommanager.data.ScoreApi
import com.daijunhao.classroommanager.data.ScoreResultData
import androidx.compose.foundation.layout.Arrangement


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ClassroomManagerTheme {
                val hasToken = TokenStore.readToken(this) != null
                MainApp(startLoggedIn = hasToken)
            }
        }
    }
}

// ---------------- 1. 路由配置 ----------------
sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Home : Screen("home", "首页", Icons.Default.Home)
    object ClassList : Screen("class_list", "班级", Icons.Default.List) // 新增班级栏
    object Mine : Screen("mine", "我的", Icons.Default.Person)
}

val bottomNavItems = listOf(Screen.Home, Screen.ClassList, Screen.Mine)

// ---------------- 2. 主页面 ----------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainApp(startLoggedIn: Boolean) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val currentScreenTitle = bottomNavItems.find { it.route == currentRoute }?.title ?: "班级小管家"
    val isMainTabScreen = bottomNavItems.any { it.route == currentRoute }

    Scaffold(
        topBar = {
            if (isMainTabScreen) {
                TopAppBar(
                    title = { Text(currentScreenTitle) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }
        },
        bottomBar = {
            if (isMainTabScreen) {
                NavigationBar {
                    bottomNavItems.forEach { screen ->
                        NavigationBarItem(
                            selected = currentRoute == screen.route,
                            onClick = {
                                if (currentRoute != screen.route) {
                                    navController.navigate(screen.route) {
                                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = { Icon(imageVector = screen.icon, contentDescription = screen.title) },
                            label = { Text(screen.title) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        // 核心修改：如果是详情页，去掉外层的 Padding 限制，让 Scaffold 的背景铺满全屏消除黑边！
        val currentPadding = if (isMainTabScreen) innerPadding else PaddingValues(0.dp)
        AppNavHost(navController, startLoggedIn, Modifier.padding(currentPadding))
    }
}

// ---------------- 3. NavHost ----------------
@Composable
fun AppNavHost(navController: NavHostController, startLoggedIn: Boolean, modifier: Modifier = Modifier) {
    val startDestination = if (startLoggedIn) Screen.Home.route else "login"

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
        // 新页面进入：从右往左滑入，同时淡入
        enterTransition = {
            slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(300)) + fadeIn(tween(300))
        },
        // 旧页面退出：向左稍微滑动，同时淡出
        exitTransition = {
            slideOutHorizontally(targetOffsetX = { -it / 3 }, animationSpec = tween(300)) + fadeOut(tween(300))
        },
        // 返回时，旧页面恢复：从左向右滑入
        popEnterTransition = {
            slideInHorizontally(initialOffsetX = { -it / 3 }, animationSpec = tween(300)) + fadeIn(tween(300))
        },
        // 返回时，新页面退出：向右滑出
        popExitTransition = {
            slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(300)) + fadeOut(tween(300))
        }
    ) {
        composable(Screen.Home.route) {
            HomeScreen(navController = navController)
        }
        composable(Screen.ClassList.route) {
            ClassScreen(onClassClick = { cid -> navController.navigate("class_detail/$cid") })
        }
        composable("class_detail/{cid}") { backStackEntry ->
            val cid = backStackEntry.arguments?.getString("cid") ?: ""
            ClassDetailScreen(cid = cid, onBack = { navController.popBackStack() })
        }
        composable(Screen.Mine.route) {
            MineScreen(onLogout = {
                navController.navigate("login") {
                    popUpTo(0) { inclusive = true }
                    launchSingleTop = true
                }
            })
        }
        composable("login") {
            LoginScreen(onLoginSuccess = {
                navController.navigate(Screen.Home.route) {
                    popUpTo("login") { inclusive = true }
                    launchSingleTop = true
                }
            })
        }
        composable("score_detail/{scoreId}") { backStackEntry ->
            val scoreId = backStackEntry.arguments?.getString("scoreId") ?: ""
            ScoreDetailScreen(scoreId = scoreId, onBack = { navController.popBackStack() })
        }
    }
}

// ---------------- 4. 具体业务页面 ----------------
@Composable
fun HomeScreen(navController: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var items by remember { mutableStateOf<List<HomeItem>>(emptyList()) }
    var page by remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(true) } // 首次加载状态
    var isLoadMore by remember { mutableStateOf(false) } // 分页加载状态
    var hasMore by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    // 统一的数据加载函数
    fun loadData(isRefresh: Boolean = false) {
        scope.launch {
            if (isRefresh) {
                isLoading = true
                page = 0
            } else {
                isLoadMore = true
            }

            val token = TokenStore.readToken(context)
            val memberId = TokenStore.readMemberId(context)

            if (token != null && memberId != null) {
                HomeApi.getHomeList(token, memberId, page).onSuccess { list ->
                    if (isRefresh) {
                        items = list
                    } else {
                        items = items + list
                    }
                    hasMore = list.size >= 20 // 假设 size 是 20，满了说明可能还有下一页
                    if (hasMore) page++
                    errorMsg = null
                }.onFailure {
                    errorMsg = it.message
                }
            } else {
                errorMsg = "登录凭证失效，请重新登录"
            }
            isLoading = false
            isLoadMore = false
        }
    }

    // 页面进入时首次拉取数据
    LaunchedEffect(Unit) {
        loadData(isRefresh = true)
    }

    if (isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    if (errorMsg != null && items.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text = "加载失败: $errorMsg", color = MaterialTheme.colorScheme.error)
        }
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        item { Spacer(modifier = Modifier.height(16.dp)) }

        // 使用 itemsIndexed，顺便判断是否滑动到了底部
        itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
            HomeCard(
                item = item,
                onClick = {
                    // 类型 4 代表成绩，并且必须有 scoreId
                    if (item.type == 4 && item.scoreId != null) {
                        navController.navigate("score_detail/${item.scoreId}")
                    }
                }
            )
        }

        // 底部 Loading 提示
        if (isLoadMore) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }
        } else if (!hasMore && items.isNotEmpty()) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("没有更多数据了", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
fun ClassScreen(onClassClick: (String) -> Unit) {
    val context = LocalContext.current
    var classList by remember { mutableStateOf<List<ClassInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val token = TokenStore.readToken(context)
        val memberId = TokenStore.readMemberId(context)

        if (token != null && memberId != null) {
            ClassApi.getClassList(token, memberId).onSuccess { list ->
                classList = list
                loading = false
            }.onFailure {
                errorMsg = it.message
                loading = false
            }
        } else {
            errorMsg = "本地凭证缺失，请重新登录"
            loading = false
        }
    }

    if (loading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    if (errorMsg != null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text = "加载失败: $errorMsg", color = MaterialTheme.colorScheme.error)
        }
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        items(classList) { clazz ->
            ClassCard(clazz = clazz, onClick = { onClassClick(clazz.id) })
        }
    }
}

@Composable
fun ClassCard(clazz: ClassInfo, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp)
            .clickable { onClick() }, // <- 添加点击跳转
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = clazz.className, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))

            SelectionContainer {
                Column {
                    DetailRow("班级口令", clazz.uniqueCode)
                    DetailRow("所属年级", clazz.grade)
                    DetailRow("学生人数", "${clazz.studentCount} / ${clazz.allCount}")
                    DetailRow("学校名称", clazz.schoolTitle)
                    DetailRow("学校地址", clazz.schoolAddress)
                }
            }
        }
    }
}

@Composable
fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(text = "$label：", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun MineScreen(onLogout: () -> Unit) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Person, contentDescription = "头像", modifier = Modifier.size(64.dp))
                Spacer(Modifier.width(16.dp))
                Text("当前用户", style = MaterialTheme.typography.headlineSmall)
            }
        }
        Text("设置", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Settings, contentDescription = "通用设置")
                Spacer(Modifier.width(16.dp))
                Text("通用设置", style = MaterialTheme.typography.bodyLarge)
            }
        }
        Spacer(Modifier.weight(1f))
        Button(
            onClick = {
                TokenStore.clear(context)
                onLogout()
            },
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        ) {
            Text("退出登录")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClassDetailScreen(cid: String, onBack: () -> Unit) {
    val context = LocalContext.current
    var members by remember { mutableStateOf<List<MemberInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(cid) {
        val token = TokenStore.readToken(context)
        if (token != null) {
            ClassApi.getMembersByCid(token, cid).onSuccess { list ->
                members = list
                loading = false
            }.onFailure {
                Toast.makeText(context, "加载成员失败: ${it.message}", Toast.LENGTH_SHORT).show()
                loading = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("班级成员 (${members.size}人)") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        // 将文字替换成标准的返回箭头图标
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        if (loading) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            SelectionContainer {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 16.dp) // 优化边距体验
                ) {
                    // 【优化1】加入 key = { it.id }，彻底杜绝无用的重组卡顿！
                    items(members, key = { it.id }) { member ->
                        MemberCard(member)
                    }
                }
            }
        }
    }
}

@Composable
fun MemberCard(member: MemberInfo) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), // 上下留一点间距即可
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = member.avatar,
                contentDescription = "头像",
                modifier = Modifier
                    .size(50.dp)
                    .clip(CircleShape)
            )
            Spacer(modifier = Modifier.width(16.dp))

            // 直接写 Column，删掉原本包在外面的 SelectionContainer
            Column {
                Text(text = member.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(text = "微信: ${member.wxName}", style = MaterialTheme.typography.bodySmall)

                if (member.phone != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "手机号: ${member.phone}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
fun HomeCard(item: HomeItem, onClick: () -> Unit) {
    // 根据类型，决定左上角标签的文字和颜色
    val tagText = when (item.type) {
        0 -> "任务"
        4 -> "成绩"
        23 -> "打卡"
        else -> "通知"
    }
    val tagColor = when (item.type) {
        0 -> MaterialTheme.colorScheme.primary
        4 -> MaterialTheme.colorScheme.error
        23 -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.tertiary
    }

    // 格式化展示时间 (把 "2026-10-23T12:02:56" 变成 "2026-10-23 12:02")
    val displayTime = if (item.endDay.isNotBlank()) {
        "截止: ${item.endDay}"
    } else {
        item.createAt.replace("T", " ").substringBeforeLast(".").take(16)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        SelectionContainer {
            Column(modifier = Modifier.padding(16.dp)) {
                // 第一行：类型标签 + 学科名 + 时间
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = tagColor,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = tagText,
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    if (item.subject.isNotBlank()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = item.subject,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    Text(
                        text = displayTime,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 第二行：标题
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                // 第三行：如果是任务，显示内容摘要 (最多2行)
                if (item.textContent.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = item.textContent,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(8.dp))

                // 第四行：发布者 + 完成状态
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = item.creatorName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    // 判断完成状态 (任务看 isFeedback，成绩看 isRead)
                    val statusText = when (item.type) {
                        0, 23 -> if (item.isFeedback) "已提交" else "未提交"
                        4 -> if (item.isRead) "已读" else "未读"
                        else -> ""
                    }
                    val isDone = item.isFeedback || item.isRead
                    val statusColor = if (isDone) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error

                    if (statusText.isNotBlank()) {
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelMedium,
                            color = statusColor,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScoreDetailScreen(scoreId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    var scoreData by remember { mutableStateOf<ScoreResultData?>(null) }
    var loading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(scoreId) {
        val token = TokenStore.readToken(context)
        if (token != null) {
            ScoreApi.getScoreDetail(token, scoreId).onSuccess { data ->
                scoreData = data
                loading = false
            }.onFailure {
                errorMsg = it.message
                loading = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("成绩详情") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        if (loading) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        if (errorMsg != null || scoreData == null) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(text = "加载失败: ${errorMsg ?: "未知错误"}", color = MaterialTheme.colorScheme.error)
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // 头部：考试标题与时间
            Text(text = scoreData!!.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = "考试时间: ${scoreData!!.examTime}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            // 成绩明细列表
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(scoreData!!.scores, key = { it.subject }) { subjectScore ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = subjectScore.subject,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            Text(
                                text = subjectScore.score,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}