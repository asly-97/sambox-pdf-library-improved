# SAMBox — bug fixes

This repository is a fork of [SAMBox](https://github.com/torakiki/sambox), an
Apache PDFBox fork used as the PDF processor for Sejda and PDFsam, written in
Java. It contains the upstream SAMBox source (version 3.0.40-SNAPSHOT, based
on upstream commit `3228b79aec1d97cf209523ff0d8b5d12125e980a`) plus a small
set of bug fixes described below.

SAMBox is licensed under the Apache License 2.0 — see `LICENSE` and
`NOTICE.txt`, kept unchanged from upstream. All credit for the library itself
goes to the SAMBox and Apache PDFBox authors. The fixes below are my own
contributions on top of their work.

Each fix is also included as a standalone patch in the `patches/` folder, so
it can be reviewed or applied on its own.

## Fix 1: A moved page kept sharing the source document's internals

**Files:** `src/main/java/org/sejda/sambox/pdmodel/PDPageTree.java`
(`patches/01-page-move-ownership-and-contents.patch`)

**Symptom.** Move a page from one PDF file into another, then keep working.
If the source document is edited afterwards, the moved page in the new
document changes too. If the source document is closed, the moved page can
lose inherited attributes (page size, rotation, resources such as fonts and
images) or end up with an empty content stream, so the saved result is wrong
or broken.

**Root cause.** A page is allowed to inherit `MediaBox`, `CropBox`, `Rotate`
and `Resources` from its ancestors in the page tree instead of defining them
itself. `PDPageTree.add()` moved the page node as-is: inherited values were
never stored on the page, the inherited `Resources` dictionary was the source
tree's own mutable dictionary (shared by reference), and the page's content
stream was a parsed stream whose bytes are read back from the source file on
demand.

**Fix.** When a page is added to a page tree, `add()` now resolves every
inherited attribute from the tree the page is leaving and stores it on the
page itself. `Resources` is stored as a deep copy (dictionaries and arrays
duplicated recursively, following indirect references), never as the source's
own dictionary. The page's `Contents` stream is also eagerly deep-copied —
including its decoded bytes — so the page is fully self-contained the moment
it is moved. Values the page defines itself are left untouched, and when no
ancestor defines a value nothing is stored, so spec fallback behaviour (for
example `CropBox` defaulting to `MediaBox`) is preserved.

**Verified.** Reproduced on the untouched code: move a page, remove entries
from the source page's resource tables, close the source, then save the
destination — the untouched code produces a wrong file. With the fix, the
same scenario produces a correct standalone file, and the full SAMBox test
suite passes.

## Fix 2: Removing a page lost the damaged-document fallbacks

**Files:** `src/main/java/org/sejda/sambox/pdmodel/PDPageTree.java`
(`patches/02-page-remove-parent-discovery.patch`)

**Symptom.** Removing a page obtained without walking the page tree (for
example from the page iterator) from a structurally damaged document could
fail to remove it or take the wrong path, because the internal removal logic
never learned which tree node actually parents the page.

**Root cause.** `PDPageTree.remove(PDPage)` called the internal removal with
no parent information, even when the page object did not carry a traversal
parent, so the fallbacks written for damaged documents received no parent to
work with.

**Fix.** `remove()` now discovers the real parent the same way `remove(int)`
does — via the page index and a tree walk — when the page does not already
know its parent, and passes it to the internal removal.

**Verified.** Covered by the project's test suite run with all fixes applied;
the untouched code fails the corresponding damaged-document removal checks.

## Fix 3: `PDAcroForm.getFields()` returned a dead snapshot

**Files:** `src/main/java/org/sejda/sambox/pdmodel/interactive/form/PDAcroForm.java`
(`patches/03-acroform-getfields-live-list.patch`)

**Symptom.** The list returned by `getFields()` was disconnected from the
document. Fields added to or removed from the returned list did not change
the form in the document, and a form whose `Fields` array did not exist yet
could never gain fields through the returned list.

**Root cause.** `getFields()` built a brand-new list from the current array
contents on every call and returned it, instead of returning a list backed
by the underlying `COSArray`.

**Fix.** `getFields()` now returns a `COSArrayList` backed by the form's
`Fields` array (creating the array on the form dictionary first when it is
missing), so reads and writes through the returned list act on the document
itself.

**Verified.** Covered by the project's test suite run with all fixes applied.

## Fix 4: Flattening crashed/misbehaved on annotations without a rectangle

**Files:** `src/main/java/org/sejda/sambox/pdmodel/interactive/form/PDAcroForm.java`
(`patches/04-flatten-missing-rect-guard.patch`)

**Symptom.** Flattening a document containing a widget annotation with no
`/Rect` entry could fail or produce wrong output, even though such an
annotation simply cannot be flattened.

**Root cause.** The flatten eligibility check never validated `/Rect`, which
ISO 32000-2 (12.5.2, Table 166) defines as required for annotations; the
code went ahead and tried to use the missing rectangle.

**Fix.** The eligibility check now treats a missing or invalid `/Rect` as
"not flattenable": it logs a warning and skips the annotation instead of
proceeding.

**Verified.** Covered by the project's test suite run with all fixes applied.

## Fix 5: Cross-reference stream parsing diverged from the table parser

**Files:** `src/main/java/org/sejda/sambox/input/AbstractXrefStreamParser.java`,
`src/main/java/org/sejda/sambox/input/XrefParser.java`
(`patches/05-xref-stream-parser-parity.patch`)

**Symptom.** Damaged or unusual PDF files whose cross-reference information
lives in an xref stream (rather than a classic xref table) could be loaded
with phantom or shifted object entries, fail with raw runtime exceptions
instead of a clean "corrupted file" error, or lose objects whose byte
offsets exceed 2 GB.

**Root cause.** Five related defects in the stream parser:
1. A missing `/Index` array defaulted to object numbers `0..Size`
   *inclusive* — one entry too many. The spec default is `[0 Size]`, i.e.
   `0..Size-1`.
2. Entries were read with a single `InputStream.read()` call, which is not
   guaranteed to fill the buffer; a short read silently misaligned every
   following entry.
3. Entry fields were accumulated into `int` values with an addition-based
   shift, so byte offsets above 2^31 in large files overflowed.
4. The `/Index` and `/W` arrays were not validated, so malformed files
   produced `NullPointerException`/`IndexOutOfBoundsException` or nonsense
   entries instead of an `IOException` signalling corruption (which is the
   contract the fallback scanner relies on).
5. The stream parser's entry consumer lacked the negative-byte-offset guard
   that the table parser's consumer already had.

**Fix.** The default `/Index` range is now `0..Size-1`; entries are read
with a read-fully loop that throws on truncation; fields are accumulated
into `long` values; `/Index` and `/W` are validated (shape, integer pairs,
non-negative counts and field lengths) with `IOException` on corruption; a
consumer rejection is converted to `IOException` with the same contract as
the table parser; and the stream entry consumer in `XrefParser` now rejects
negative byte offsets the same way the table side does.

**Verified.** Each defect was reproduced on the untouched code using crafted
PDF files (missing `/Index`, zero-count ranges, odd `/Index`, missing `/W`,
offsets beyond 2 GB, hybrid files), and the fixed parser loads them with the
same results as the classic table parser. The project's test suite passes
with the fix applied.

## Build / environment notes (not library bugs)

These are local build workarounds only. They are **not** part of the fixes
above, are not applied to the code in this repository, and are recorded here
just so the work is reproducible:

- The upstream `pom.xml` downloads two IPA font files at build time from a
  host (osdn) that no longer serves them, which breaks an offline build.
  For local offline work those two download executions (`PDFBOX-4106`,
  `PDFBOX-4106b`) were commented out of the pom.
- Three test methods in `TestFontEmbedding` that need those IPA fonts were
  commented out locally for the same reason.
- The build expects a JDK 17 toolchain; locally this was provided through a
  Maven `toolchains.xml` pointing at a local JDK 17 install.

## Layout

- Upstream SAMBox source with fixes 1–5 applied.
- `patches/` — each fix as an individual, reviewable patch file.
- `README-UPSTREAM.md` — the original upstream README, unchanged.
