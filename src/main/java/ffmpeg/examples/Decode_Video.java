package ffmpeg.examples;

//import java.io.FileInputStream;
//import java.io.FileOutputStream;
//import java.io.IOException;
//
//import static org.bytedeco.javacpp.avutil.*;
//import static org.bytedeco.javacpp.avcodec.*;
//
//import org.bytedeco.javacpp.BytePointer;
//
//public class Decode_Video {
//
//	static final int INBUF_SIZE = 4096;
//
//	static void pgm_save(BytePointer buf, int wrap, int xsize, int ysize, String filename) throws Exception {
//	    int i;
//
//	    FileOutputStream f = new FileOutputStream(filename);
//
//	    f.write(String.format("P5\n%d %d\n%d\n", xsize, ysize, 255).getBytes());
//	    for (i = 0; i < ysize; i++)
//	    	for (int j = 0; i < xsize; j++)
//	    		f.write(buf.get(i*wrap+j));
//	    
//	    f.close();
//	}
//
//	static void decode(AVCodecContext dec_ctx, AVFrame frame, AVPacket pkt, String filename) throws Exception {
//	    int ret;
//
//	    ret = avcodec_send_packet(dec_ctx, pkt);
//	    if (ret < 0)
//	        throw new Exception("Error sending a packet for decoding\n");
//
//	    while (ret >= 0) {
//	        ret = avcodec_receive_frame(dec_ctx, frame);
//	        if (ret == AVERROR_EAGAIN() || ret == AVERROR_EOF)
//	            return;
//
//	        if (ret < 0)
//	        	throw new Exception("Error during decoding\n");
//
//	        System.out.println("saving frame "+dec_ctx.frame_number());
//
//	        /* the picture is allocated by the decoder. no need to free it */
//	        pgm_save(frame.data(0), frame.linesize(0), frame.width(), frame.height(), filename+"-"+dec_ctx.frame_number());
//	    }
//	}
//
//	public static void main(String[] args) {
//	    String filename, outfilename;
//
//	    int ret;
//
//	    AVCodec codec;
//	    AVCodecParserContext parser;
//	    AVCodecContext c = null;
//
//	    AVFrame frame;
//	    AVPacket pkt;
//
//	    FileInputStream f;
//
//	    uint8_t inbuf[INBUF_SIZE + AV_INPUT_BUFFER_PADDING_SIZE];
//	    uint8_t *data;
//	    size_t   data_size;
//
//	    filename    = "UntilYou-ShayneWard_6xrx.mp4";
//	    outfilename = "Out_";
//
//	    pkt = av_packet_alloc();
//	    if (pkt.isNull())
//	    	System.exit(1);
//
//	    /* set end of buffer to 0 (this ensures that no overreading happens for damaged MPEG streams) */
//	    memset(inbuf + INBUF_SIZE, 0, AV_INPUT_BUFFER_PADDING_SIZE);
//
//	    /* find the MPEG-1 video decoder */
//	    codec = avcodec_find_decoder(AV_CODEC_ID_MPEG1VIDEO);
//	    if (codec.isNull())
//	    	throw new Exception("Codec not found");
//
//	    parser = av_parser_init(codec.id());
//	    if (parser.isNull())
//	    	throw new Exception("parser not found");
//
//	    c = avcodec_alloc_context3(codec);
//	    if (c.isNull())
//	    	throw new Exception("Could not allocate video codec context");
//
//	    /* For some codecs, such as msmpeg4 and mpeg4, width and height
//	       MUST be initialized there because this information is not
//	       available in the bitstream. */
//
//	    /* open it */
//	    if (avcodec_open2(c, codec, new AVDictionary(null)) < 0)
//	    	throw new Exception("Could not open codec");
//
//	    f = new FileInputStream(filename);
//
//	    frame = av_frame_alloc();
//	    if (frame.isNull())
//	    	throw new Exception("Could not allocate video frame");
//
//	    while (f.available() > 0) {
//	        /* read raw data from the input file */
//	        data_size = fread(inbuf, 1, INBUF_SIZE, f);
//	        if (!data_size)
//	            break;
//
//	        /* use the parser to split the data into frames */
//	        data = inbuf;
//	        while (data_size > 0) {
//	            ret = av_parser_parse2(parser, c, pkt.data(), pkt.size(),
//	                                   data, data_size, AV_NOPTS_VALUE, AV_NOPTS_VALUE, 0);
//	            if (ret < 0) {
//	                fprintf(stderr, "Error while parsing\n");
//	                exit(1);
//	            }
//	            data      += ret;
//	            data_size -= ret;
//
//	            if (pkt.size() > 0)
//	                decode(c, frame, pkt, outfilename);
//	        }
//	    }
//
//	    /* flush the decoder */
//	    decode(c, frame, null, outfilename);
//
//	    f.close();
//
//	    av_parser_close(parser);
//	    avcodec_free_context(c);
//	    av_frame_free(frame);
//	    av_packet_free(pkt);
//	}
//	
//}
