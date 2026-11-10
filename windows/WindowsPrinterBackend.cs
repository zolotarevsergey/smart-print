using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.Drawing.Printing;
using System.Management;
using System.Runtime.Versioning;
using Windows.Data.Pdf;
using Windows.Storage.Streams;

namespace SportikPrint;

/// <summary>
/// Друк через драйвер Windows: PDF рендериться Windows.Data.Pdf у картинку на сторінку, далі GDI
/// (System.Drawing.Printing) — так само, як друкує будь-яка програма на цьому ПК.
/// </summary>
[SupportedOSPlatform("windows10.0.17763.0")]
public sealed class WindowsPrinterBackend(PrintOptions opts, ILogger<WindowsPrinterBackend> logger) : IPrinterBackend
{
    const string PreferredModel = "MF3010";

    // WMI (Win32_Printer / Win32_PrintJob) зі служби LocalSystem може висіти хвилинами —
    // стан принтера лише «для краси», тож кожен такий запит має межу часу
    const int WmiTimeoutMs = 4000;

    public PrinterStatus GetStatus()
    {
        var installedStep = Timed(() => PrinterSettings.InstalledPrinters.Cast<string>().ToArray(), 5000);
        if (installedStep.Error is not null)
            return new PrinterStatus("", false, "Windows не віддає список принтерів: " + installedStep.Error, 0, []);
        var installed = installedStep.Value!;

        string printer;
        try
        {
            printer = ResolvePrinter(installed);
        }
        catch (Exception ex)
        {
            return new PrinterStatus("", false, ex.Message, 0, installed);
        }

        var wmi = Timed(() => QueryWmi(printer), WmiTimeoutMs);
        if (wmi.Error is not null)
            return new PrinterStatus(printer, true, "Готовий (стан принтера Windows не віддала)", 0, installed);
        var (ready, state, jobs) = wmi.Value;
        return new PrinterStatus(printer, ready, state, jobs, installed);
    }

    /// <summary>Покроково, з часом кожного кроку — щоб побачити, що саме висить на конкретному ПК.</summary>
    public object Diagnose()
    {
        var steps = new List<object>();
        object Step<T>(string name, Func<T> f, int timeoutMs)
        {
            var sw = System.Diagnostics.Stopwatch.StartNew();
            var r = Timed(f, timeoutMs);
            var row = new { step = name, ms = sw.ElapsedMilliseconds, ok = r.Error is null, result = r.Error ?? Describe(r.Value) };
            steps.Add(row);
            return row;
        }

        string[] installed = [];
        Step("Список принтерів (EnumPrinters)", () => installed = PrinterSettings.InstalledPrinters.Cast<string>().ToArray(), 15000);
        Step("Принтер за замовчуванням", () => new PrinterSettings().PrinterName, 15000);
        Step("WMI Win32_Printer (Name, ShareName)", () => ShareNames().Select(s => $"{s.Name} [{s.Share}]").ToArray(), 15000);
        string? printer = null;
        Step("Вибраний принтер", () => printer = ResolvePrinter(installed), 15000);
        if (printer is not null)
        {
            Step("PrinterSettings.IsValid", () =>
            {
                var ps = new PrinterSettings { PrinterName = printer };
                return $"valid={ps.IsValid}, папір: {string.Join(", ", ps.PaperSizes.Cast<PaperSize>().Take(8).Select(p => p.PaperName))}";
            }, 15000);
            Step("WMI стан принтера і черга", () => QueryWmi(printer), 15000);
        }

        return new
        {
            user = Environment.UserDomainName + "\\" + Environment.UserName,
            interactive = Environment.UserInteractive,
            os = Environment.OSVersion.VersionString,
            steps,
        };
    }

    static string Describe(object? v) => v switch
    {
        null => "null",
        string s => s,
        string[] a => string.Join(" | ", a),
        _ => v.ToString() ?? "",
    };

    readonly record struct TimedResult<T>(T? Value, string? Error);

    static TimedResult<T> Timed<T>(Func<T> f, int timeoutMs)
    {
        var task = Task.Run(f);
        try
        {
            if (!task.Wait(timeoutMs)) return new(default, $"не відповіло за {timeoutMs / 1000} с");
            return new(task.Result, null);
        }
        catch (AggregateException ex)
        {
            return new(default, ex.InnerException?.Message ?? ex.Message);
        }
    }

    public PrintResult Print(PrintRequest r)
    {
        var printer = ResolvePrinter(PrinterSettings.InstalledPrinters.Cast<string>().ToArray());
        using var doc = new PrintDocument();
        doc.PrinterSettings.PrinterName = printer;
        if (!doc.PrinterSettings.IsValid) throw new InvalidOperationException($"Принтер «{printer}» недоступний");
        doc.DocumentName = r.Title;
        // без вікна «Друкується сторінка N» — у службі його нема де показати
        doc.PrintController = new StandardPrintController();
        doc.OriginAtMargins = false;
        ApplyMedia(doc, r.Media);

        int pages = r.Kind switch
        {
            DocumentKind.Pdf => PrintPdf(doc, r),
            DocumentKind.Image => PrintImage(doc, r),
            DocumentKind.Text => PrintText(doc, r),
            _ => throw new InvalidOperationException("Невідомий формат"),
        };
        logger.LogInformation("Надруковано «{Title}» на {Printer}: {Pages} стор. × {Copies}", r.Title, printer, pages, r.Copies);
        return new PrintResult(pages, printer);
    }

    // ── PDF ──────────────────────────────────────────────────────────────────────────────

    int PrintPdf(PrintDocument doc, PrintRequest r)
    {
        var pdf = LoadPdf(r.Data);
        var count = (int)pdf.PageCount;
        if (count == 0) throw new InvalidOperationException("PDF без сторінок");

        var sizes = new SizeF[count];
        for (var i = 0; i < count; i++)
        {
            using var p = pdf.GetPage((uint)i);
            sizes[i] = new SizeF((float)p.Size.Width, (float)p.Size.Height);
        }

        // копії розбираємо самі (1,2,3,1,2,3) — не всі драйвери вміють Copies
        var total = count * r.Copies;
        var k = 0;
        doc.QueryPageSettings += (_, e) => e.PageSettings.Landscape = sizes[k % count].Width > sizes[k % count].Height;
        doc.PrintPage += (_, e) =>
        {
            var idx = k % count;
            using (var bmp = RenderPdfPage(pdf, (uint)idx, opts.RenderDpi))
            {
                // сторінка у дюймах → сотих дюйма (одиниці Graphics за замовчуванням)
                var pageSize = new SizeF(sizes[idx].Width / 96f * 100f, sizes[idx].Height / 96f * 100f);
                var target = r.Fit ? PrintableRect(e) : TrueScaleRect(e, pageSize);
                DrawImage(e.Graphics!, bmp, target);
            }
            k++;
            e.HasMorePages = k < total;
        };
        doc.Print();
        return count;
    }

    static PdfDocument LoadPdf(byte[] data)
    {
        var ras = new InMemoryRandomAccessStream();
        using (var w = new DataWriter(ras))
        {
            w.WriteBytes(data);
            w.StoreAsync().AsTask().GetAwaiter().GetResult();
            w.FlushAsync().AsTask().GetAwaiter().GetResult();
            w.DetachStream();
        }
        ras.Seek(0);
        try
        {
            return PdfDocument.LoadFromStreamAsync(ras).AsTask().GetAwaiter().GetResult();
        }
        catch (Exception ex)
        {
            throw new InvalidOperationException("PDF не читається (захищений паролем або пошкоджений): " + ex.Message, ex);
        }
    }

    static Bitmap RenderPdfPage(PdfDocument pdf, uint index, int dpi)
    {
        using var page = pdf.GetPage(index);
        var options = new PdfPageRenderOptions
        {
            DestinationWidth = (uint)Math.Max(1, Math.Round(page.Size.Width / 96.0 * dpi)),
            DestinationHeight = (uint)Math.Max(1, Math.Round(page.Size.Height / 96.0 * dpi)),
            BackgroundColor = Windows.UI.Color.FromArgb(255, 255, 255, 255),
        };
        using var ras = new InMemoryRandomAccessStream();
        page.RenderToStreamAsync(ras, options).AsTask().GetAwaiter().GetResult();
        using var s = ras.AsStreamForRead();
        using var decoded = new Bitmap(s);
        return new Bitmap(decoded); // копія не тримає потік
    }

    // ── Картинки ─────────────────────────────────────────────────────────────────────────

    static int PrintImage(PrintDocument doc, PrintRequest r)
    {
        using var ms = new MemoryStream(r.Data);
        using var img = Image.FromStream(ms);
        ApplyExifOrientation(img);

        var frames = img.RawFormat.Equals(ImageFormat.Tiff) ? img.GetFrameCount(FrameDimension.Page) : 1;
        var total = frames * r.Copies;
        var k = 0;
        doc.QueryPageSettings += (_, e) => e.PageSettings.Landscape = img.Width > img.Height;
        doc.PrintPage += (_, e) =>
        {
            if (frames > 1) img.SelectActiveFrame(FrameDimension.Page, k % frames);
            DrawImage(e.Graphics!, img, PrintableRect(e));
            k++;
            e.HasMorePages = k < total;
        };
        doc.Print();
        return frames;
    }

    /// <summary>Телефони пишуть поворот кадру в EXIF (0x0112), а пікселі лишають як з матриці.</summary>
    static void ApplyExifOrientation(Image img)
    {
        const int OrientationId = 0x0112;
        if (!img.PropertyIdList.Contains(OrientationId)) return;
        var v = img.GetPropertyItem(OrientationId)?.Value;
        if (v is null || v.Length == 0) return;
        var flip = v[0] switch
        {
            2 => RotateFlipType.RotateNoneFlipX,
            3 => RotateFlipType.Rotate180FlipNone,
            4 => RotateFlipType.Rotate180FlipX,
            5 => RotateFlipType.Rotate90FlipX,
            6 => RotateFlipType.Rotate90FlipNone,
            7 => RotateFlipType.Rotate270FlipX,
            8 => RotateFlipType.Rotate270FlipNone,
            _ => RotateFlipType.RotateNoneFlipNone,
        };
        if (flip == RotateFlipType.RotateNoneFlipNone) return;
        img.RotateFlip(flip);
        img.RemovePropertyItem(OrientationId);
    }

    // ── Текст ────────────────────────────────────────────────────────────────────────────

    static int PrintText(PrintDocument doc, PrintRequest r)
    {
        var text = DocumentKinds.DecodeText(r.Data).Replace("\r\n", "\n").Replace('\t', ' ');
        using var font = new Font(FontFamily.Families.Any(f => f.Name == "Consolas") ? "Consolas" : "Courier New", 10f);
        using var fmt = new StringFormat(StringFormat.GenericTypographic) { Trimming = StringTrimming.Word };

        var pagesPerCopy = 0;
        var copy = 0;
        var rest = text;
        doc.PrintPage += (_, e) =>
        {
            var g = e.Graphics!;
            var area = e.MarginBounds;
            area.Offset(-(int)e.PageSettings.HardMarginX, -(int)e.PageSettings.HardMarginY);
            g.MeasureString(rest, font, area.Size, fmt, out var chars, out int _);
            if (chars == 0 && rest.Length > 0) chars = rest.Length; // щоб не зациклитися на гігантському «слові»
            g.DrawString(rest[..chars], font, Brushes.Black, area, fmt);
            rest = rest[chars..];
            if (copy == 0) pagesPerCopy++;

            if (rest.Length == 0 && ++copy < r.Copies) rest = text;
            e.HasMorePages = rest.Length > 0;
        };
        doc.Print();
        return pagesPerCopy;
    }

    // ── Геометрія ────────────────────────────────────────────────────────────────────────

    /// <summary>Уся сторінка в координатах Graphics (початок — у кутку поля друку, тобто зсунутий на жорсткі поля).</summary>
    static RectangleF PageRect(PrintPageEventArgs e) => new(
        -e.PageSettings.HardMarginX, -e.PageSettings.HardMarginY, e.PageBounds.Width, e.PageBounds.Height);

    /// <summary>Те, куди принтер реально кладе тонер: сторінка мінус жорсткі поля з усіх боків.</summary>
    static RectangleF PrintableRect(PrintPageEventArgs e)
    {
        var mx = e.PageSettings.HardMarginX;
        var my = e.PageSettings.HardMarginY;
        return new RectangleF(0, 0, e.PageBounds.Width - 2 * mx, e.PageBounds.Height - 2 * my);
    }

    /// <summary>Сторінка PDF у справжньому розмірі по центру аркуша (A4 на A4 = 1:1); більша — зменшується.</summary>
    static RectangleF TrueScaleRect(PrintPageEventArgs e, SizeF pdfPage)
    {
        var page = PageRect(e);
        var scale = Math.Min(1f, Math.Min(page.Width / pdfPage.Width, page.Height / pdfPage.Height));
        // різниця в 2 % — це той самий формат паперу, лише округлення — тягнемо на весь аркуш
        if (Math.Abs(page.Width - pdfPage.Width) / page.Width < 0.02f && Math.Abs(page.Height - pdfPage.Height) / page.Height < 0.02f)
            return page;
        var w = pdfPage.Width * scale;
        var h = pdfPage.Height * scale;
        return new RectangleF(page.X + (page.Width - w) / 2, page.Y + (page.Height - h) / 2, w, h);
    }

    static void DrawImage(Graphics g, Image img, RectangleF box)
    {
        var scale = Math.Min(box.Width / img.Width, box.Height / img.Height);
        var w = img.Width * scale;
        var h = img.Height * scale;
        g.InterpolationMode = InterpolationMode.HighQualityBicubic;
        g.PixelOffsetMode = PixelOffsetMode.HighQuality;
        g.DrawImage(img, box.X + (box.Width - w) / 2, box.Y + (box.Height - h) / 2, w, h);
    }

    static void ApplyMedia(PrintDocument doc, string media)
    {
        PaperKind? kind = media switch
        {
            "ISO_A4" => PaperKind.A4,
            "ISO_A5" => PaperKind.A5,
            "ISO_A6" => PaperKind.A6,
            "ISO_B5" => PaperKind.B5,
            "NA_LETTER" => PaperKind.Letter,
            "NA_LEGAL" => PaperKind.Legal,
            _ => null,
        };
        if (kind is null) return;
        var paper = doc.PrinterSettings.PaperSizes.Cast<PaperSize>().FirstOrDefault(p => p.Kind == kind);
        if (paper is not null) doc.DefaultPageSettings.PaperSize = paper;
    }

    // ── Принтер ──────────────────────────────────────────────────────────────────────────

    string ResolvePrinter(IReadOnlyList<string> installed)
    {
        if (installed.Count == 0) throw new InvalidOperationException("На цьому ПК немає встановлених принтерів");

        if (!string.IsNullOrWhiteSpace(opts.Printer))
        {
            var exact = installed.FirstOrDefault(p => string.Equals(p, opts.Printer, StringComparison.OrdinalIgnoreCase));
            return exact ?? throw new InvalidOperationException($"Принтера «{opts.Printer}» немає. Є: {string.Join(", ", installed)}");
        }

        var byName = installed.FirstOrDefault(p => p.Contains(PreferredModel, StringComparison.OrdinalIgnoreCase));
        if (byName is not null) return byName;

        var shares = Timed(() => ShareNames().ToList(), WmiTimeoutMs).Value ?? [];
        var byShare = shares.FirstOrDefault(s => s.Share.Contains(PreferredModel, StringComparison.OrdinalIgnoreCase)).Name;
        if (byShare is not null) return byShare;

        // служба працює від LocalSystem, у неї свій «принтер за замовчуванням» — тому лише як запас
        var def = new PrinterSettings().PrinterName;
        return installed.Contains(def) ? def : installed[0];
    }

    static IEnumerable<(string Name, string Share)> ShareNames()
    {
        var list = new List<(string, string)>();
        try
        {
            using var s = new ManagementObjectSearcher("SELECT Name, ShareName FROM Win32_Printer");
            foreach (var o in s.Get())
                list.Add(((string?)o["Name"] ?? "", (string?)o["ShareName"] ?? ""));
        }
        catch
        {
            // WMI недоступний — обійдемося іменами
        }
        return list;
    }

    static (bool Ready, string State, int Jobs) QueryWmi(string printer)
    {
        try
        {
            var name = printer.Replace("\\", "\\\\").Replace("'", "\\'");
            using var s = new ManagementObjectSearcher(
                $"SELECT WorkOffline, PrinterStatus, DetectedErrorState FROM Win32_Printer WHERE Name = '{name}'");
            var o = s.Get().Cast<ManagementObject>().FirstOrDefault();
            if (o is null) return (false, "Принтер не знайдено", 0);

            var jobs = 0;
            using (var j = new ManagementObjectSearcher("SELECT Name FROM Win32_PrintJob"))
                jobs = j.Get().Cast<ManagementObject>().Count(x => ((string?)x["Name"] ?? "").StartsWith(printer + ",", StringComparison.OrdinalIgnoreCase));

            if (o["WorkOffline"] is true) return (false, "Вимкнений або від'єднаний (offline)", jobs);
            var error = Convert.ToInt32(o["DetectedErrorState"] ?? 0);
            var errorText = error switch
            {
                4 => "Немає паперу",
                6 => "Немає тонера",
                7 => "Відкрита кришка",
                8 => "Застряг папір",
                9 => "Offline",
                10 => "Потрібне обслуговування",
                11 => "Повний лоток виводу",
                _ => null,
            };
            if (errorText is not null) return (false, errorText, jobs);
            var st = Convert.ToInt32(o["PrinterStatus"] ?? 0);
            var stText = st switch
            {
                3 => "Готовий",
                4 => "Друкує",
                5 => "Прогрівається",
                6 => "Зупинений",
                7 => "Offline",
                _ => "Готовий",
            };
            return (st != 7 && st != 6, stText, jobs);
        }
        catch (Exception ex)
        {
            return (true, "Стан невідомий (" + ex.Message + ")", 0);
        }
    }
}
