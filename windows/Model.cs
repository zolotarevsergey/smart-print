using System.Text;

namespace SportikPrint;

public sealed class PrintOptions
{
    public int Port { get; set; } = 8631;
    public string Printer { get; set; } = "";
    public string Token { get; set; } = "";
    public int RenderDpi { get; set; } = 300;
    public int MaxUploadMb { get; set; } = 100;
}

public enum DocumentKind { Unknown, Pdf, Image, Text }

/// <param name="Media">Розмір паперу з Android (ISO_A4, ISO_A5, NA_LETTER…); порожньо — як у драйвері.</param>
/// <param name="Fit">Для PDF: вписати сторінку в поле друку замість масштабу 1:1.</param>
public sealed record PrintRequest(byte[] Data, DocumentKind Kind, string Title, int Copies, string Media, bool Fit);

public sealed record PrintResult(int Pages, string Printer);

public sealed record PrinterStatus(string Printer, bool Ready, string State, int QueuedJobs, string[] Printers);

public interface IPrinterBackend
{
    PrinterStatus GetStatus();
    PrintResult Print(PrintRequest request);
    object Diagnose();
}

public static class DocumentKinds
{
    public static DocumentKind Detect(byte[] d, string? contentType)
    {
        if (StartsWith(d, "%PDF"u8)) return DocumentKind.Pdf;
        if (StartsWith(d, [0xFF, 0xD8, 0xFF])) return DocumentKind.Image;                // JPEG
        if (StartsWith(d, [0x89, 0x50, 0x4E, 0x47])) return DocumentKind.Image;          // PNG
        if (StartsWith(d, "GIF8"u8) || StartsWith(d, "BM"u8)) return DocumentKind.Image; // GIF, BMP
        if (StartsWith(d, "II*\0"u8) || StartsWith(d, "MM\0*"u8)) return DocumentKind.Image; // TIFF

        var ct = (contentType ?? "").ToLowerInvariant();
        if (ct.StartsWith("text/") || LooksLikeText(d)) return DocumentKind.Text;
        return DocumentKind.Unknown;
    }

    public static string DecodeText(byte[] d)
    {
        if (StartsWith(d, [0xEF, 0xBB, 0xBF])) return Encoding.UTF8.GetString(d, 3, d.Length - 3);
        if (StartsWith(d, [0xFF, 0xFE])) return Encoding.Unicode.GetString(d, 2, d.Length - 2);
        try
        {
            return new UTF8Encoding(false, throwOnInvalidBytes: true).GetString(d);
        }
        catch (DecoderFallbackException)
        {
            // старі текстові файли з Windows — кирилиця cp1251
            Encoding.RegisterProvider(CodePagesEncodingProvider.Instance);
            return Encoding.GetEncoding(1251).GetString(d);
        }
    }

    static bool LooksLikeText(byte[] d)
    {
        var n = Math.Min(d.Length, 4096);
        for (var i = 0; i < n; i++)
            if (d[i] == 0) return false;
        return true;
    }

    static bool StartsWith(byte[] d, ReadOnlySpan<byte> prefix) => d.AsSpan().StartsWith(prefix);
}

/// <summary>Журнал завдань: %ProgramData%\SportikPrint\print.log, по рядку на завдання.</summary>
public sealed class JobLog
{
    readonly string _path;
    readonly object _lock = new();

    public JobLog()
    {
        var dir = OperatingSystem.IsWindows()
            ? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), "SportikPrint")
            : AppContext.BaseDirectory;
        Directory.CreateDirectory(dir);
        _path = Path.Combine(dir, "print.log");
    }

    public void Write(string line)
    {
        lock (_lock)
        {
            try
            {
                var fi = new FileInfo(_path);
                if (fi.Exists && fi.Length > 5 * 1024 * 1024)
                    File.Move(_path, _path + ".old", overwrite: true);
                File.AppendAllText(_path, $"{DateTime.Now:yyyy-MM-dd HH:mm:ss}\t{line}{Environment.NewLine}");
            }
            catch
            {
                // журнал не має валити друк
            }
        }
    }
}

/// <summary>Не Windows (перевірка на Mac/Linux): нічого не друкує, складає файли в ./received.</summary>
public sealed class DryRunBackend(ILogger<DryRunBackend> logger) : IPrinterBackend
{
    readonly string _dir = Path.Combine(AppContext.BaseDirectory, "received");

    public PrinterStatus GetStatus() =>
        new("Dry run (файли в received/)", true, "Готовий (перевірка без принтера)", 0, ["Dry run"]);

    public object Diagnose() => new { dryRun = true, dir = _dir };

    public PrintResult Print(PrintRequest r)
    {
        Directory.CreateDirectory(_dir);
        var ext = r.Kind switch { DocumentKind.Pdf => ".pdf", DocumentKind.Image => ".img", _ => ".txt" };
        var safe = string.Concat(r.Title.Select(ch => Path.GetInvalidFileNameChars().Contains(ch) ? '_' : ch));
        var file = Path.Combine(_dir, $"{DateTime.Now:HHmmss}_{safe}_x{r.Copies}_{r.Media}{ext}");
        File.WriteAllBytes(file, r.Data);
        var pages = r.Kind == DocumentKind.Pdf ? Math.Max(1, CountPdfPages(r.Data)) : 1;
        logger.LogInformation("Dry run: {File} ({Pages} стор.)", file, pages);
        return new PrintResult(pages, "Dry run");
    }

    static int CountPdfPages(byte[] d)
    {
        var s = Encoding.Latin1.GetString(d);
        var n = 0;
        for (var i = s.IndexOf("/Type", StringComparison.Ordinal); i >= 0; i = s.IndexOf("/Type", i + 5, StringComparison.Ordinal))
        {
            var j = i + 5;
            while (j < s.Length && (s[j] == ' ' || s[j] == '\n' || s[j] == '\r')) j++;
            if (string.CompareOrdinal(s, j, "/Page", 0, 5) == 0 && (j + 5 >= s.Length || !char.IsLetter(s[j + 5]))) n++;
        }
        return n;
    }
}
