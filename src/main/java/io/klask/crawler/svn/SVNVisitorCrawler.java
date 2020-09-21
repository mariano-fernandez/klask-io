package io.klask.crawler.svn;

import io.klask.config.Constants;
import io.klask.crawler.impl.SVNCrawler;
import io.klask.domain.File;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tmatesoft.svn.core.SVNCommitInfo;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.SVNProperty;
import org.tmatesoft.svn.core.SVNPropertyValue;
import org.tmatesoft.svn.core.internal.util.SVNPathUtil;
import org.tmatesoft.svn.core.internal.wc.SVNFileUtil;
import org.tmatesoft.svn.core.io.ISVNEditor;
import org.tmatesoft.svn.core.io.diff.SVNDeltaProcessor;
import org.tmatesoft.svn.core.io.diff.SVNDiffWindow;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Stack;

/**
 * Created by harelj on 06/03/2017.
 */
public class SVNVisitorCrawler implements ISVNEditor {
    private final Logger log = LoggerFactory.getLogger(SVNVisitorCrawler.class);
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    File currentFile = new File();
    SvnProgressCanceller svnProgressCanceller;
    private Stack<String> myDirectoriesStack = new Stack<>();

    private boolean skipTags = false;
    private boolean currentFileReadable = false;
    private boolean currentFileExcluded = false;
    private long currentSize=-1;
    private SVNDeltaProcessor myDeltaProcessor = new SVNDeltaProcessor();
    private SVNCrawler svnCrawler;
    private Map<String, Long> updatedFiles = new HashMap<>();
    private Map<String, Long> deletedFiles = new HashMap<>();

    //if crawler get 'trunk', 'tags' or 'branches' the currentProject is the directory just above
    private String currentProject = null;
    //if crawler get 'trunk', 'tags' or 'branches' the currentBranch is the directory just below
    private String currentBranch = null;

    public SVNVisitorCrawler(SVNCrawler svnCrawler, SvnProgressCanceller svnProgressCanceller) {
        this.svnCrawler = svnCrawler;
        this.svnProgressCanceller = svnProgressCanceller;
    }

    @Override
    public void abortEdit() throws SVNException {
        log.trace("abortEdit");
    }

    @Override
    public void absentDir(String path) throws SVNException {
        log.trace("absentDir {}", path);
    }

    @Override
    public void absentFile(String path) throws SVNException {
        log.trace("absentFile {}", path);
    }

    @Override
    public void openFile(String path, long revision) throws SVNException {
        if (skipTags) return;
        log.trace("openFile {}:{}", path, revision);
        updatedFiles.put(path, revision);
        currentFileExcluded = true;//will be added out of this
    }

    @Override
    public void addFile(String path, String copyFromPath, long copyFromRevision) throws SVNException {
        if (skipTags) return;
        log.trace("addFileInCurrentBranch {}, copyFromPath={}, copyFromRevision={}", path, copyFromPath, copyFromRevision);
        outputStream.reset();
        currentFileReadable = this.svnCrawler.isReadableExtension(path);
        currentFileExcluded = this.svnCrawler.isFileInExclusion(Paths.get(path));
        if (!currentFileExcluded) {
            currentFile = this.svnCrawler.createFile(path);
        } else {
            currentFile = null;
        }
    }

    @Override
    public SVNCommitInfo closeEdit() throws SVNException {
        log.trace("closeEdit");
        return null;
    }

    //in the closeFile, the param md5Checksum give the MD5 check sum
    @Override
    public void closeFile(String path, String md5Checksum) throws SVNException {
        if(skipTags || currentFileExcluded)return;
        log.trace("closeFile {}:{}", path, md5Checksum);
        if (currentFileReadable) {
            currentFile.setContent(new String(outputStream.toByteArray(), Charset.forName("iso-8859-1")));

        }
        currentFile.setSize(currentSize);//TODO fix the int => long problem
        currentFile.setProject(currentProject);
        currentFile.setVersion(currentBranch);
        this.svnCrawler.addFile(currentFile);

    }

    @Override
    public void deleteEntry(String path, long revision) throws SVNException {
        if(skipTags) return;
        log.trace("deleteEntry {} : {}", path, revision);
        deletedFiles.put(this.svnCrawler.getRepository().getPath() + "/" + path, revision);
    }



    @Override
    public void targetRevision(long revision) throws SVNException {
        log.trace("targetRevision {}", revision);
    }

    @Override
    public void applyTextDelta(String path, String baseChecksum) throws SVNException {
        if (skipTags) return;
        log.trace("applyTextDelta {} ck {}", path, baseChecksum);
        if (!currentFileExcluded && currentFileReadable) {
            myDeltaProcessor.applyTextDelta(null, outputStream, false);
        }
    }

    @Override
    public OutputStream textDeltaChunk(String path, SVNDiffWindow diffWindow) throws SVNException {
        if(skipTags)return SVNFileUtil.DUMMY_OUT;
        log.trace("textDeltaChunk {}:{}", path, diffWindow);
        currentSize = diffWindow.getTargetViewLength();
        if (currentSize > Constants.MAX_SIZE_FOR_INDEXING_ONE_FILE) {
            currentFileReadable = false;
        }
        if (currentFileExcluded || !currentFileReadable) {
            return SVNFileUtil.DUMMY_OUT;
        }
        return myDeltaProcessor.textDeltaChunk( diffWindow );
    }

    @Override
    public void textDeltaEnd(String path) throws SVNException {
        if(skipTags)return;
        log.trace("textDeltaEnd {}", path);
        if (!currentFileExcluded && currentFileReadable) {
            myDeltaProcessor.textDeltaEnd();
        }
    }

    @Override
    public void addDir(String path, String copyFromPath, long copyFromRevision) throws SVNException {
        svnProgressCanceller.checkCancelled();
        log.trace("addDir {}, copyFromPath={}, copyFromRevision={}", path, copyFromPath, copyFromRevision);
        if (path != null) {
            if (path.endsWith("/tags")) {
                //if path end with /tags, it shouldn't contains "trunk" or "branches" in the tail path like
                // /http//myserver/svn/myproject/trunk/mymodule/tags
                String tail = SVNPathUtil.removeTail(path);
                if (!tail.contains("/trunk") && !tail.contains("/branches") && !tail.contains("/tags")) {
                    skipTags = true;
                }
            }
            if (currentProject == null && (path.endsWith("/trunk") || path.endsWith("/branches"))) {
                String lastDir = myDirectoriesStack.peek();
                this.currentProject = lastDir.substring(lastDir.lastIndexOf('/') + 1);
            }

            if (myDirectoriesStack.peek().endsWith("/branches")  ) {
                currentBranch = path.substring(path.lastIndexOf('/') + 1);
            }
            if (myDirectoriesStack.peek().endsWith("/trunk")) {
                currentBranch = "trunk";
            }

        }

        String absoluteDirPath = "/" + path;
        myDirectoriesStack.push(absoluteDirPath);
    }

    @Override
    public void changeDirProperty(String name, SVNPropertyValue value) throws SVNException {
        if (skipTags) return;
        //filter out svn:entry and svn:wc properties since we are interested in regular properties only
//        if (!SVNProperty.isRegularProperty(name)) {
//            return;
//        }


//        String currentDirPath = myDirectoriesStack.peek();
//        Map props = (Map) myDirProps.get(currentDirPath);
//        if (props == null) {
//            props = new HashMap();
//            myDirProps.put(currentDirPath, props);
//        }
//        props.put(name, value);
    }

    @Override
    public void changeFileProperty(String path, String propertyName, SVNPropertyValue propertyValue) throws SVNException {
        //log.trace("property {} : {}",propertyName,propertyValue);
        //filter out svn:entry and svn:wc properties since we are interested in regular properties only
//        if (!SVNProperty.isRegularProperty(propertyName)) {
//            return;
//        }
        if (skipTags || currentFile == null) return;

        switch(propertyName){
            case "svn:entry:last-author":
                currentFile.setLastAuthor(propertyValue.getString());
                break;
            case "svn:mime-type":
                currentFileReadable = SVNProperty.isTextMimeType(propertyValue.getString());
                break;
            case "svn:executable":
                currentFileReadable = false;
                break;
            case "svn:entry:committed-date":
                currentFile.setLastDate(propertyValue.getString());
                break;
            case "svn:entry:committed-rev":
                break;
            case "svn:entry:uuid":
                //we do nothing
                break;
            default:
                log.trace("new property {}={}", propertyName, propertyValue.getString());
        }

//        String absolutePath = "/" + path;
//        Map props = (Map) myFileProps.get(absolutePath);
//        if (props == null) {
//            props = new HashMap();
//            myFileProps.put(absolutePath, props);
//        }
//        props.put(propertyName, propertyValue);
    }

    @Override
    public void closeDir() throws SVNException {
        String last = myDirectoriesStack.pop();
        log.trace("closeDir {}", last);
        if (last != null) {
            if (last.endsWith("/tags")) {
                //if path end with /tags, it shouldn't contains "trunk" or "branches" in the tail path like
                // /http//myserver/svn/myproject/trunk/mymodule/tags
                String tail = SVNPathUtil.removeTail(last);
                if (!tail.contains("/trunk") && !tail.contains("/branches") && !tail.contains("/tags")) {
                    skipTags = false;
                }
            }
            if (last.endsWith("/branches") || last.endsWith("/trunk")) {
                String tail = SVNPathUtil.removeTail(last);
                if (!tail.contains("/trunk") && !tail.contains("/branches") && !tail.contains("/tags")) {
                    currentProject = null;
                    currentBranch = null;
                }
            }
        }


    }

    @Override
    public void openDir(String path, long revision) throws SVNException {
        svnProgressCanceller.checkCancelled();
        log.trace("openDir {} : {}", path, revision);
        String absoluteDirPath = "/" + path;
        if (path != null) {
            if (path.endsWith("/tags")) {
                //if path end with /tags, it shouldn't contains "trunk" or "branches" in the tail path like
                // /http//myserver/svn/myproject/trunk/mymodule/tags
                String tail = SVNPathUtil.removeTail(path);
                if (!tail.contains("/trunk") && !tail.contains("/branches") && !tail.contains("/tags")) {
                    skipTags = true;
                }
            }
            if (currentProject == null && (path.endsWith("/trunk") || path.endsWith("/branches"))) {
                String lastDir = myDirectoriesStack.peek();
                this.currentProject = lastDir.substring(lastDir.lastIndexOf('/') + 1);
            }

            if (myDirectoriesStack.peek().endsWith("/branches")) {
                currentBranch = path.substring(path.lastIndexOf('/') + 1);
            }
            if (myDirectoriesStack.peek().endsWith("/trunk")) {
                currentBranch = "trunk";
            }

        }
        myDirectoriesStack.push(absoluteDirPath);
    }

    @Override
    public void openRoot(long revision) throws SVNException {
        log.trace("openRoot : {}", revision);
        String absoluteDirPath = this.svnCrawler.getRepository().getPath();
        myDirectoriesStack.push(absoluteDirPath);
    }

    public Map<String, Long> getUpdatedFiles() {
        return updatedFiles;
    }

    public Map<String, Long> getDeletedFiles() {
        return deletedFiles;
    }
}
