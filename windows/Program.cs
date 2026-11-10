using SportikPrint;

// Служба друку для Android: приймає PDF / фото / текст по HTTP і друкує їх через Windows-драйвер
// принтера (для Canon MF3010 це UFR II LT, якого телефон сам не вміє). Протокол — у README.md.

var builder = WebApplication.CreateBuilder(new WebApplicationOptions
{
    Args = args,
    // служба Windows стартує з C:\Windows\System32 — конфіг беремо поруч з exe
    ContentRootPath = AppContext.BaseDirectory,
});
builder.Host.UseWindowsService(o => o.ServiceName = "SportikPrint");

var opts = builder.Configuration.GetSection("Print").Get<PrintOptions>() ?? new PrintOptions();
builder.WebHost.ConfigureKestrel(k =>
{
    k.ListenAnyIP(opts.Port);
    k.Limits.MaxRequestBodySize = (long)opts.MaxUploadMb * 1024 * 1024;
});
builder.Services.AddSingleton(opts);
builder.Services.AddSingleton<JobLog>();
builder.Services.AddSingleton<IPrinterBackend>(sp => OperatingSystem.IsWindows()
    ? new WindowsPrinterBackend(opts, sp.GetRequiredService<ILogger<WindowsPrinterBackend>>())
    : new DryRunBackend(sp.GetRequiredService<ILogger<DryRunBackend>>()));

var app = builder.Build();
var jobs = new SemaphoreSlim(1, 1); // драйвер один — друкуємо по черзі

bool Authorized(HttpContext ctx) =>
    string.IsNullOrEmpty(opts.Token) || ctx.Request.Headers["X-Print-Token"] == opts.Token;

app.MapGet("/", () => Results.Text("SportikPrint " + Version() + " — служба друку для Android. Див. /api/status\n"));

app.MapGet("/api/status", (HttpContext ctx, IPrinterBackend backend) =>
{
    if (!Authorized(ctx)) return Results.Json(new { ok = false, error = "Невірний ключ" }, statusCode: 401);
    var st = backend.GetStatus();
    return Results.Json(new
    {
        ok = true,
        version = Version(),
        machine = Environment.MachineName,
        printer = st.Printer,
        ready = st.Ready,
        state = st.State,
        queuedJobs = st.QueuedJobs,
        printers = st.Printers,
        dryRun = backend is DryRunBackend,
    });
});

// покрокова перевірка (принтери, WMI, драйвер) з часом кожного кроку — для розбору «чому висить»
app.MapGet("/api/diag", (HttpContext ctx, IPrinterBackend backend) =>
    Authorized(ctx)
        ? Results.Json(backend.Diagnose())
        : Results.Json(new { ok = false, error = "Невірний ключ" }, statusCode: 401));

app.MapPost("/api/print", async (HttpContext ctx, IPrinterBackend backend, JobLog log) =>
{
    if (!Authorized(ctx)) return Results.Json(new { ok = false, error = "Невірний ключ" }, statusCode: 401);

    var q = ctx.Request.Query;
    var copies = int.TryParse(q["copies"], out var c) ? Math.Clamp(c, 1, 99) : 1;
    var title = string.IsNullOrWhiteSpace(q["title"]) ? "Android" : q["title"].ToString();
    var media = q["media"].ToString();
    var fit = q["fit"] == "1";

    byte[] data;
    using (var ms = new MemoryStream())
    {
        await ctx.Request.Body.CopyToAsync(ms);
        data = ms.ToArray();
    }
    if (data.Length == 0) return Results.Json(new { ok = false, error = "Порожній файл" }, statusCode: 400);

    var kind = DocumentKinds.Detect(data, ctx.Request.ContentType);
    if (kind == DocumentKind.Unknown)
        return Results.Json(new { ok = false, error = "Невідомий формат (потрібен PDF, JPEG, PNG, BMP, GIF або текст)" }, statusCode: 415);

    var client = ctx.Connection.RemoteIpAddress?.ToString() ?? "?";
    if (!await jobs.WaitAsync(TimeSpan.FromMinutes(5)))
        return Results.Json(new { ok = false, error = "Попереднє завдання досі друкується — спробуйте пізніше" }, statusCode: 503);
    try
    {
        // драйвер, що завис (у службі LocalSystem таке буває), не має вішати телефон назавжди
        var printTask = Task.Run(() => backend.Print(new PrintRequest(data, kind, title, copies, media, fit)));
        if (await Task.WhenAny(printTask, Task.Delay(TimeSpan.FromMinutes(3))) != printTask)
            throw new TimeoutException("Драйвер принтера не відповідає 3 хв. Якщо це повторюється — перевстановіть службу командою install.cmd -AsUser");
        var result = await printTask;
        log.Write($"{client}\t{kind}\t{data.Length / 1024} КБ\t{copies} прим.\t{result.Pages} стор.\t{result.Printer}\tOK\t{title}");
        return Results.Json(new { ok = true, pages = result.Pages, printer = result.Printer });
    }
    catch (Exception ex)
    {
        app.Logger.LogError(ex, "Друк не вдався");
        log.Write($"{client}\t{kind}\t{data.Length / 1024} КБ\t{copies} прим.\t-\t-\tПОМИЛКА {ex.Message}\t{title}");
        return Results.Json(new { ok = false, error = ex.Message }, statusCode: 500);
    }
    finally
    {
        jobs.Release();
    }
});

app.Logger.LogInformation("SportikPrint {Version} слухає порт {Port}", Version(), opts.Port);
app.Run();

static string Version() =>
    typeof(PrintOptions).Assembly.GetName().Version?.ToString(3) ?? "?";
