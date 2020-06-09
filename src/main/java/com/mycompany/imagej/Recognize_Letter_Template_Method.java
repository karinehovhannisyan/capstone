package com.mycompany.imagej;

import ij.*;

import java.awt.*;

import ij.plugin.filter.PlugInFilter;
import ij.plugin.filter.RankFilters;
import ij.process.*;

import java.awt.geom.Line2D;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.Math;

import ij.ImagePlus;

import javax.imageio.ImageIO;
import java.util.ArrayList;
import java.util.Comparator;

public class Recognize_Letter_Template_Method implements PlugInFilter {
    int LINE_COLOR = 0;
    private ImagePlus im;
    private ArrayList<Integer> spaces;
    private ArrayList<int[]> lines, words;

    public int setup(String args, ImagePlus im) {
        lines = new ArrayList<int[]>();
        words = new ArrayList<int[]>();
        spaces = new ArrayList<Integer>();
        this.im = im;
        return DOES_8G;
    }

    public void run(ImageProcessor ip) {
        ip.threshold(170); // make binary

        // crop the assignment part
        int x1 = 276, y1 = 720, x2 = 2442, y2 = 2706;
        ip.setRoi(new Rectangle(x1, y1, x2 - x1, y2 - y1));
        ip = ip.crop();
        ip.reset();
        this.im.setProcessor(ip);
        ip.reset(ip.getMask());
        // end crop

        // remove salt and pepper
        RankFilters rankFilter = new RankFilters();
        rankFilter.rank(ip, 2, RankFilters.MEDIAN);
        // end remove

        // setup the data
        DivideLines(ip);
        separateLines(words);

        // end setup

        // calculate traits
        double baseline = baseLineWithCentroid(ip);
        double slant = slant(ip);
        int[] margins = margins(ip);
        int size = size(ip);
        double wordSpacing = wordSpacing();
        double speed = speed(ip);
        double pressure = pressure(ip);
//        // end calculate

        writeResultsToFile(im.getShortTitle(), baseline);
    }


    public double baseLineWithCentroid(ImageProcessor ip) {
        double[] result = new double[]{0, 0};
        for (int[] line : lines) {
            int length = line[2] - line[0];
            double[][] centroids = new double[4][];
            ArrayList<double[]> difs = new ArrayList<double[]>();
            for (int i = 0; i < 4; i++) {
                double[] centroid = centerOfGravity(ip, line[0] + ((length * i) / 4), line[1], line[0] + ((length * (i + 1)) / 4), line[3]);
                centroids[i] = centroid;
                for (int j = 0; j < i; j++) {
                    difs.add(new double[]{
                            Math.abs(centroids[i][0] - centroids[j][0]),
                            Math.abs(centroids[i][1] - centroids[j][1])
                    });
                }
            }
            double xAvg = 0, yAvg = 0;
            for (double[] dif : difs) {
                xAvg += dif[0];
                yAvg += dif[1];
            }
            yAvg /= 6;
            xAvg /= 6;
            result[0] = Math.max(result[0], xAvg);
            result[1] = Math.max(result[1], yAvg);
        }
        return result[1];
    }

    public double baseLineWithSlope(ImageProcessor ip) {
        double result = 0;
        for (int[] word : words) {
            int biggestTopY = ip.getHeight(), smallestTopY = 0, biggestBottomY = ip.getHeight(), smallestBottomY = 0;
            int biggestTopX = ip.getWidth(), smallestTopX = 0, biggestBottomX = ip.getWidth(), smallestBottomX = 0;
            for (int i = word[0]; i <= word[2]; i++) {
                for (int j = word[1]; j <= word[3]; j++) {
                    if (ip.getPixel(i, j) == 0) {
                        if (j < biggestTopY) {
                            biggestTopY = j;
                            biggestTopX = i;
                        }
                        if (j > biggestTopY) {
                            smallestTopY = j;
                            smallestTopX = i;
                        }
                        break;
                    }
                }
                for (int j = word[3]; j >= word[1]; j--) {
                    if (ip.getPixel(i, j) == 0) {
                        if (j < biggestTopY) {
                            biggestBottomY = j;
                            biggestBottomX = i;
                        }
                        if (j > biggestTopY) {
                            smallestBottomY = j;
                            smallestBottomX = i;
                        }
                        break;
                    }
                }
            }

            Point bigTop = new Point(biggestTopX, biggestTopY);
            Point smallTop = new Point(smallestTopX, smallestTopY);
            Point bigB = new Point(biggestBottomX, biggestBottomY);
            Point smallB = new Point(smallestBottomX, smallestBottomY);


            double avgForWord = (slope(bigTop, smallTop) + slope(bigB, smallB)) / 2;
            result = Math.max(result, avgForWord);
        }
        return result;
    }

    public double slantWithSlope(ImageProcessor ip) {
        double result = 0;
        for (int[] word : words) {
            int biggestLeftY = ip.getHeight(), smallestLeftY = 0, biggestRightY = ip.getHeight(), smallestRightY = 0;
            int biggestLeftX = ip.getHeight(), smallestLeftX = 0, biggestRightX = ip.getHeight(), smallestRightX = 0;
            for (int j = word[2]; j <= word[3]; j++) {
                for (int i = word[0]; i <= word[1]; i++) {
                    if (ip.getPixel(i, j) == 0) {
                        if (i < biggestLeftX) {
                            biggestLeftY = j;
                            biggestLeftX = i;
                        }
                        if (i > biggestLeftX) {
                            smallestLeftY = j;
                            smallestLeftX = i;
                        }
                        break;
                    }
                }
                for (int i = word[1]; i >= word[0]; j--) {
                    if (ip.getPixel(i, j) == 0) {
                        if (i < biggestLeftX) {
                            biggestRightY = j;
                            biggestRightX = i;
                        }
                        if (i > biggestLeftX) {
                            smallestRightY = j;
                            smallestRightX = i;
                        }
                        break;
                    }
                }
            }

            Point bigTop = new Point(biggestLeftX, biggestLeftY);
            Point smallTop = new Point(smallestLeftX, smallestLeftY);
            Point bigB = new Point(biggestRightX, biggestRightY);
            Point smallB = new Point(smallestRightX, smallestRightY);


            double avgForWord = (slope(bigTop, smallTop) + slope(bigB, smallB)) / 2;
            result = Math.max(result, avgForWord);
        }
        return result;
    }

    public double sobelFilter(ImageProcessor ip, int startWithX, int startWithY, int endWithX, int endWithY) {
        double result = 0;
        int sobel_x[][] = {{-1, 0, 1},
                {-2, 0, 2},
                {-1, 0, 1}};
        int sobel_y[][] = {{-1, -2, -1},
                {0, 0, 0},
                {1, 2, 1}};
        int pixel_x, pixel_y;
        for (int x = startWithX + 1; x < endWithX - 2; x++) {
            for (int y = startWithY + 1; y < endWithY - 2; y++) {
                pixel_x = (sobel_x[0][0] * ip.getPixel(x - 1, y - 1)) + (sobel_x[0][1] * ip.getPixel(x, y - 1)) + (sobel_x[0][2] * ip.getPixel(x + 1, y - 1)) +
                        (sobel_x[1][0] * ip.getPixel(x - 1, y)) + (sobel_x[1][1] * ip.getPixel(x, y)) + (sobel_x[1][2] * ip.getPixel(x + 1, y)) +
                        (sobel_x[2][0] * ip.getPixel(x - 1, y + 1)) + (sobel_x[2][1] * ip.getPixel(x, y + 1)) + (sobel_x[2][2] * ip.getPixel(x + 1, y + 1));
                pixel_y = (sobel_y[0][0] * ip.getPixel(x - 1, y - 1)) + (sobel_y[0][1] * ip.getPixel(x, y - 1)) + (sobel_y[0][2] * ip.getPixel(x + 1, y - 1)) +
                        (sobel_y[1][0] * ip.getPixel(x - 1, y)) + (sobel_y[1][1] * ip.getPixel(x, y)) + (sobel_y[1][2] * ip.getPixel(x + 1, y)) +
                        (sobel_y[2][0] * ip.getPixel(x - 1, y + 1)) + (sobel_y[2][1] * ip.getPixel(x, y + 1)) + (sobel_x[2][2] * ip.getPixel(x + 1, y + 1));

                result += Math.sqrt((pixel_x * pixel_x) + (pixel_y * pixel_y));
            }
        }
        return result / ((endWithX - startWithX - 3) * (endWithY - startWithY - 3));
    }

    public double rawMoment(ImageProcessor ip, int x, int y) {
        
    }

    public double slant(ImageProcessor ip) {
        int result = 0;
        for (int[] line : lines) {
            int height = line[3] - line[1];
            int sumOfC = 0;
            for (int i = 0; i < 8; i++) {
                double[] centroid = centerOfGravity(ip, line[0], line[1] + ((height * i) / 4), line[2], ((height * (i + 1)) / 4) + line[3]);
                sumOfC += (centroid[0] - centroid[1]);
            }
            result = Math.max(result, sumOfC / 4);
        }
        return result;
    }

    public int size(ImageProcessor ip) { // TODO rewrite with baselines
        int result = 0;
        for (int i = 0; i < ip.getWidth(); i++)
            for (int j = 0; j < ip.getHeight(); j++)
                if (ip.getPixel(i, j) > 0)
                    result++;
        return result;
    }

    public double sizeV2(ImageProcessor ip) {
        double result = 0, overallTop = 0, overallBot = 0;
        for (int[] line : lines) {
            double topAvg = 0, botAvg = 0;
            for (int i = line[0]; i <= line[2]; i++) {
                for (int j = line[1]; j <= line[3]; j++) {
                    if (ip.getPixel(i, j) == 0) {
                        topAvg += j;
                        break;
                    }
                }
                for (int j = line[3]; j >= line[1]; j--) {
                    if (ip.getPixel(i, j) == 0) {
                        botAvg += j;
                        break;
                    }
                }
            }


        }
        return result;
    }

    public double wordSpacing() {
        int result = 0, q = 0;
        for (int i = 0; i < (spaces.size() * 5 / 8) + 1; i++) {
            result += spaces.get(i);
            q++;
        }
        return (double) result / q;
    }

    public int[] margins(ImageProcessor ip) {
        int right = 0, left = ip.getWidth() - 1;
        for (int[] line : lines) {
            left = Math.min(line[0], left);
            right = Math.max(line[2], right);
        }
        return new int[]{left, right};
    }

    public double speed(ImageProcessor ip) {
        double result = 0;
        for (int[] word : words) {
            int biggestTopY = ip.getHeight(), smallestTopY = 0, biggestBottomY = ip.getHeight(), smallestBottomY = 0;
            int biggestTopX = ip.getWidth(), smallestTopX = 0, biggestBottomX = ip.getWidth(), smallestBottomX = 0;
            for (int i = word[0]; i <= word[2]; i++) {
                for (int j = word[1]; j <= word[3]; j++) {
                    if (ip.getPixel(i, j) == 0) {
                        if (j < biggestTopY) {
                            biggestTopY = j;
                            biggestTopX = i;
                        }
                        if (j > biggestTopY) {
                            smallestTopY = j;
                            smallestTopX = i;
                        }
                        break;
                    }
                }
                for (int j = word[3]; j >= word[1]; j--) {
                    if (ip.getPixel(i, j) == 0) {
                        if (j < biggestTopY) {
                            biggestBottomY = j;
                            biggestBottomX = i;
                        }
                        if (j > biggestTopY) {
                            smallestBottomY = j;
                            smallestBottomX = i;
                        }
                        break;
                    }
                }
            }

            Point bigTop = new Point(biggestTopX, biggestTopY);
            Point smallTop = new Point(smallestTopX, smallestTopY);
            Point bigB = new Point(biggestBottomX, biggestBottomY);
            Point smallB = new Point(smallestBottomX, smallestBottomY);

            Line2D.Double top = new Line2D.Double(bigTop, smallTop);
            Line2D.Double bottom = new Line2D.Double(bigB, smallB);
            int density = 0;
            for (int i = word[0]; i < word[2]; i++)
                for (int j = word[1]; j < word[3]; j++) {
                    if (isUnderLine(i, j, top) && !isUnderLine(i, j, bottom) && ip.getPixel(i, j) > 0) {
                        density++;
                        if (word[2] - i <= 20 && ip.getPixel(i, j) > 0)
                            density++;
                    }
                }
            result += density;
        }
        return result / words.size();
    }

    public double pressure(ImageProcessor ip) {
        // TODO
        return 0;
    }

    private boolean isUnderLine(int x, int y, Line2D line) {
        return true;
    }

    private void separateLines(ArrayList<int[]> words) {
        int lastLineBottom = words.get(words.size() - 1)[3];
        int[] firstWord = words.get(0);
        int j = 1;
        while (firstWord[3] < lastLineBottom && j < words.size()) {
            firstWord = words.get(j);
            int right = firstWord[2];
            for (int i = j; i < words.size(); i++) {
                int[] word = words.get(i);
                if (firstWord[1] == word[1]) {
                    right = word[2];
                } else {
                    j = i + 1;
                    break;
                }
            }
            this.lines.add(new int[]{firstWord[0], firstWord[1], right, firstWord[3]});
        }
    }

    private double[] centerOfGravity(ImageProcessor ip, int x1, int y1, int x2, int y2) {
        double cgx = 0, cgy = 0, x = 0;
        for (int i = x1; i <= x2; i++) {
            x += i;
            for (int j = y1; j <= y2; j++) {
                int pixel = ip.getPixel(i, j) > 0 ? 0 : 1;
                cgx = cgx + (pixel * i);
                cgy = cgy + (pixel * j);
            }
        }
        int num = (y2 - y1 + 1) * (x2 - x1 + 1);
        return new double[]{cgx / num, cgy / num};
    }

    private Double weight = 0.;

    private double slope(Point a, Point b) {
        return ((double) (b.y - a.y)) / (b.x - a.x);
    }

    private void DivideLines(ImageProcessor ip) {
        int left, top, right, bottom = -1;
        ArrayList<int[]> wordsPreliminary = new ArrayList<int[]>();
        ArrayList<int[]> wordsFinal = new ArrayList<int[]>();
        ArrayList<Integer> spaces = new ArrayList<Integer>();
        while (bottom < ip.getHeight()) {
            top = FindTopLine(ip, bottom + 1);
            bottom = FindBottomLine(ip, top + 1);
            right = -1;
            while (right < ip.getWidth()) {
                left = FindLeftLine(ip, right + 1, top, bottom);
                if (right != -1 && left != -1)
                    spaces.add(left - right);
                right = FindRightLine(ip, left + 1, top, bottom);
                if (right != -1 && left != -1)
                    wordsPreliminary.add(new int[]{left, right, top, bottom});
                else break;
            }
        }
        int q3 = spaces.size() * 5 / 8;
        spaces.sort(new Comparator<Integer>() {
            public int compare(Integer o1, Integer o2) {
                return o1 - o2;
            }
        });
        this.spaces = spaces;
        int threshold = spaces.get(q3);
        for (int i = 0; i < wordsPreliminary.size(); i++) {
            int[] f = wordsPreliminary.get(i);
            int t = f[2], j = i + 1, r = f[1];
            while (j < wordsPreliminary.size() && wordsPreliminary.get(j)[2] == t) {
                int[] s = wordsPreliminary.get(j);
                if (s[0] - r < threshold) {
                    r = s[1];
                } else {
                    i = j - 1;
                    break;
                }
                j++;
            }
            // drawBox(ip, f[0], f[2], r, f[3]);
            wordsFinal.add(new int[]{f[0], f[2], r, f[3]}); // left top right bottom
        }
        this.words = wordsFinal;
    }

    private int FindTopLine(ImageProcessor ip, int startFrom) {
        int lowestTop = ip.getHeight();
        for (int j = startFrom; j < ip.getHeight(); j++) {
            int sum = 0;
            for (int i = 0; i < ip.getWidth(); i++) {
                if (ip.getPixel(i, j) == 0)
                    sum++;
            }
            if (sum > 0 && sum <= ip.getWidth() * 0.7) {
                lowestTop = j;
                weight = (weight + ((double) sum / (ip.getWidth()))) / 2;
                break;
            }
        }
        return lowestTop;
    }

    private int FindBottomLine(ImageProcessor ip, int startFrom) {
        int highestBottom = ip.getHeight();
        for (int j = startFrom; j < ip.getHeight(); j++) {
            int sum = 0;
            for (int i = 0; i < ip.getWidth(); i++) {
                if (ip.getPixel(i, j) == 0)
                    sum++;
            }
            if (sum <= ip.getWidth() * (weight * 0.75) && Math.abs(j - startFrom) >= 60) {
                highestBottom = j;
                break;
            } else {
                weight = (weight + ((double) sum / (ip.getWidth()))) / 2;
            }
        }
        return highestBottom;
    }

    private int FindLeftLine(ImageProcessor ip, int startFrom, int minHeight, int maxHeight) {
        int leftMost = -1;
        for (int i = startFrom; i < ip.getWidth(); i++) {
            int sum = 0;
            for (int j = minHeight; j < maxHeight; j++) {
                if (ip.getPixel(i, j) == 0)
                    sum++;
            }
            if (sum != 0) {
                leftMost = i;
                break;
            }
        }
        return leftMost;
    }

    private int FindRightLine(ImageProcessor ip, int startFrom, int minHeight, int maxHeight) {
        int rightMost = ip.getWidth();
        for (int i = startFrom; i < ip.getWidth(); i++) {
            int sum = 0;
            for (int j = minHeight; j < maxHeight; j++) {
                if (ip.getPixel(i, j) == 0)
                    sum++;
            }
            if (sum == 0) {
                rightMost = i;
                break;
            }
        }
        return rightMost;
    }

    private void drawBox(ImageProcessor drawBoxIp, int x1, int y1, int x2, int y2) {
        drawHorizontalLine(drawBoxIp, y2, x1, x2);
        drawHorizontalLine(drawBoxIp, y1, x1, x2);
        drawVerticalLine(drawBoxIp, x1, y1, y2);
        drawVerticalLine(drawBoxIp, x2, y1, y2);
    }

    private void drawVerticalLine(ImageProcessor ip, int col, int rowStart, int rowEnd) {
        for (int row = rowStart; row < rowEnd; row++) {
            ip.putPixel(col, row, LINE_COLOR);
        }
    }

    private void drawHorizontalLine(ImageProcessor ip, int row, int colStart, int colEnd) {
        for (int col = colStart; col < colEnd; col++) {
            ip.putPixel(col, row, LINE_COLOR);
        }
    }

    private void writeResultsToFile(String filename, double baseline) {
        try {
            File myObj = new File(filename + ".txt");
            if (myObj.createNewFile()) {
                FileWriter myWriter = new FileWriter(filename + ".txt");
                myWriter.write("Baseline " + baseline);
                myWriter.close();
            } else {
                FileWriter myWriter = new FileWriter(filename + ".txt");
                myWriter.write("Baseline " + baseline);
                myWriter.close();
            }
        } catch (IOException e) {
            System.out.println("An error occurred.");
            e.printStackTrace();
        }
    }

    public static void main(String[] args) throws Exception {
        Class<?> clazz = Recognize_Letter_Template_Method.class;
        java.net.URL url = clazz.getProtectionDomain().getCodeSource().getLocation();
        java.io.File file = new java.io.File(url.toURI());
        System.setProperty("plugins.dir", file.getAbsolutePath());

        File folder = new File("C:\\Users\\37491\\Documents\\capstone\\handwriting\\formsA-D");
        File[] listOfFiles = folder.listFiles();
        new ImageJ();

        for (int i = 0; i < listOfFiles.length; i++) {
            File img = listOfFiles[i];
            Image readImg = ImageIO.read(img);
            if (img.isFile() && readImg != null) {

                // open the Clown sample
                ImagePlus image = IJ.openImage(img.getAbsolutePath());
                image.show();

                // run the plugin
                IJ.runPlugIn(clazz.getName(), "");

                image.show();
                image.changes = false;
                image.close();
            }
        }
//        // start ImageJ
//        ImagePlus image = IJ.openImage("C:\\Users\\37491\\Documents\\capstone\\handwriting\\formsA-D\\a01-000u.png");
//        ImagePlus image = IJ.openImage("C:\\Users\\37491\\Documents\\capstone\\handwriting\\formsA-D\\a01-003.png");
//        image.show();
//
//        // run the plugin
//        IJ.runPlugIn(clazz.getName(), "");
////
//        image.show();
    }

}